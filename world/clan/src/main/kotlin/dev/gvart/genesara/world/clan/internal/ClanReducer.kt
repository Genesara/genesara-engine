package dev.gvart.genesara.world.clan.internal

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.world.AddMemberOutcome
import dev.gvart.genesara.world.ClanAction
import dev.gvart.genesara.world.ClanId
import dev.gvart.genesara.world.ClanInvite
import dev.gvart.genesara.world.ClanInviteId
import dev.gvart.genesara.world.ClanInviteStore
import dev.gvart.genesara.world.ClanRank
import dev.gvart.genesara.world.ClanRegistry
import dev.gvart.genesara.world.CreateClanOutcome
import dev.gvart.genesara.world.FactionRegistry
import dev.gvart.genesara.world.WorldRejection
import dev.gvart.genesara.world.commands.ClanCommand
import dev.gvart.genesara.world.events.ClanEvent
import dev.gvart.genesara.world.events.FactionEvent
import dev.gvart.genesara.world.events.WorldEvent
import dev.gvart.genesara.world.internal.balance.BalanceLookup
import dev.gvart.genesara.world.internal.worldstate.ReducerOutput
import dev.gvart.genesara.world.internal.worldstate.slices.CoreSlice
import java.util.UUID

/**
 * Clan-lifecycle reducers — Create / Leave / Dissolve / TransferLeadership (#22).
 *
 * Clan state is persisted in Postgres via [ClanRegistry], not in any world slice;
 * these reducers take the [CoreSlice] and return it unchanged via [ReducerOutput.sliceDelta],
 * performing registry writes as side-effects (mirrors the Redis-backed party reducers).
 * The single-threaded `WorldTickHandler` reduces one command at a time per world, so the
 * registry reads + writes within a reducer body see no concurrent clan mutation.
 *
 * Clan formation is a pure social act — no co-location / presence gate (#22), so these
 * reducers do not read agent positions.
 */
fun reduceCreateClan(
    core: CoreSlice,
    command: ClanCommand.CreateClan,
    clans: ClanRegistry,
    tick: Long,
): Either<WorldRejection, ReducerOutput<CoreSlice>> = either {
    when (val outcome = clans.createClan(command.name, command.agent, tick)) {
        is CreateClanOutcome.Created -> ReducerOutput(
            sliceDelta = core,
            events = listOf(
                ClanEvent.ClanJoined(
                    clanId = outcome.clan.id,
                    agent = command.agent,
                    clanRank = ClanRank.ARCHON,
                    listeners = setOf(command.agent),
                    tick = tick,
                    causedBy = command.commandId,
                ),
            ),
        )

        CreateClanOutcome.NameTaken ->
            raise(WorldRejection.ClanNameTaken(command.agent, command.name))

        is CreateClanOutcome.AlreadyInClan ->
            raise(WorldRejection.AlreadyInClan(command.agent, outcome.existing.value))
    }
}

fun reduceLeaveClan(
    core: CoreSlice,
    command: ClanCommand.LeaveClan,
    clans: ClanRegistry,
    factions: FactionRegistry,
    agents: AgentRegistry,
    tick: Long,
): Either<WorldRejection, ReducerOutput<CoreSlice>> = either {
    val membership = ensureNotNull(clans.clanOf(command.agent)) { WorldRejection.NotInAnyClan(command.agent) }
    val clanId = membership.clan.id
    val inFaction = membership.clan.factionId != null

    if (membership.clanRank == ClanRank.ARCHON) {
        // Sole Archon must hand off before leaving; an Archon who is the last member
        // dissolves the clan by leaving (#22 succession rule).
        ensure(clans.memberCount(clanId) <= 1) { WorldRejection.MustHandOffLeadership(command.agent, clanId.value) }
        val events = mutableListOf<WorldEvent>()
        if (inFaction) events += pullClanFromFaction(clanId, factions, agents, tick, command.commandId)
        val former = clans.dissolve(clanId)
        events += ClanEvent.ClanDissolved(clanId, former.toSet(), tick, command.commandId)
        ReducerOutput(sliceDelta = core, events = events)
    } else {
        val listeners = clans.roster(clanId).map { it.agentId }.toSet()
        clans.removeMember(clanId, command.agent)
        // The departing member leaves the faction with their clan membership; clear the mirror.
        if (inFaction) agents.setFactionRank(command.agent, null)
        ReducerOutput(
            sliceDelta = core,
            events = listOf(
                ClanEvent.ClanLeft(clanId, command.agent, ClanEvent.ClanLeft.Reason.LEFT, listeners, tick, command.commandId),
            ),
        )
    }
}

fun reduceDissolveClan(
    core: CoreSlice,
    command: ClanCommand.DissolveClan,
    clans: ClanRegistry,
    factions: FactionRegistry,
    agents: AgentRegistry,
    tick: Long,
): Either<WorldRejection, ReducerOutput<CoreSlice>> = either {
    val membership = ensureNotNull(clans.clanOf(command.agent)) { WorldRejection.NotInAnyClan(command.agent) }
    val clanId = membership.clan.id
    ensure(membership.clanRank == ClanRank.ARCHON) { WorldRejection.NotClanArchon(command.agent, clanId.value) }
    val events = mutableListOf<WorldEvent>()
    if (membership.clan.factionId != null) events += pullClanFromFaction(clanId, factions, agents, tick, command.commandId)
    val former = clans.dissolve(clanId)
    events += ClanEvent.ClanDissolved(clanId, former.toSet(), tick, command.commandId)
    ReducerOutput(sliceDelta = core, events = events)
}

/**
 * Detach [clanId] from its faction as part of dissolving it: clears every member's faction rank
 * (authoritative `clan_members` row + the `:player` mirror that drives the slot bonus) and, if the
 * clan was the faction's last member, deletes the faction. Returns the events to emit. Must run
 * BEFORE [ClanRegistry.dissolve] deletes the clan — it reads the membership the faction still holds.
 */
private fun pullClanFromFaction(
    clanId: ClanId,
    factions: FactionRegistry,
    agents: AgentRegistry,
    tick: Long,
    causedBy: UUID,
): List<WorldEvent> {
    val result = factions.leaveFaction(clanId)
    result.clearedAgents.forEach { agents.setFactionRank(it, null) }
    val events = mutableListOf<WorldEvent>()
    val remaining = factions.agentsInFaction(result.factionId).toSet()
    events += FactionEvent.FactionLeft(result.factionId, clanId, result.clearedAgents.toSet() + remaining, tick, causedBy)
    if (result.factionEmptied) {
        factions.deleteFaction(result.factionId)
        events += FactionEvent.FactionDissolved(result.factionId, result.clearedAgents.toSet(), tick, causedBy)
    }
    return events
}

fun reduceTransferClanLeadership(
    core: CoreSlice,
    command: ClanCommand.TransferClanLeadership,
    clans: ClanRegistry,
    tick: Long,
): Either<WorldRejection, ReducerOutput<CoreSlice>> = either {
    ensure(command.target != command.agent) { WorldRejection.CannotTransferToSelf(command.agent) }
    val membership = ensureNotNull(clans.clanOf(command.agent)) { WorldRejection.NotInAnyClan(command.agent) }
    val clanId = membership.clan.id
    ensure(membership.clanRank == ClanRank.ARCHON) { WorldRejection.NotClanArchon(command.agent, clanId.value) }
    val targetMembership = ensureNotNull(clans.clanOf(command.target)) {
        WorldRejection.TransferTargetNotClanMember(command.agent, command.target, clanId.value)
    }
    ensure(targetMembership.clan.id == clanId) {
        WorldRejection.TransferTargetNotClanMember(command.agent, command.target, clanId.value)
    }
    val targetPreviousRank = targetMembership.clanRank
    clans.changeClanRank(clanId, command.agent, ClanRank.VANGUARD)
    clans.changeClanRank(clanId, command.target, ClanRank.ARCHON)
    val listeners = clans.roster(clanId).map { it.agentId }.toSet()
    ReducerOutput(
        sliceDelta = core,
        events = listOf(
            ClanEvent.RankChanged(clanId, command.agent, ClanRank.ARCHON, ClanRank.VANGUARD, listeners, tick, command.commandId),
            ClanEvent.RankChanged(clanId, command.target, targetPreviousRank, ClanRank.ARCHON, listeners, tick, command.commandId),
        ),
    )
}

fun reduceClanInvite(
    core: CoreSlice,
    command: ClanCommand.InviteToClan,
    clans: ClanRegistry,
    clanInvites: ClanInviteStore,
    balance: BalanceLookup,
    tickIntervalSeconds: Long,
    tick: Long,
): Either<WorldRejection, ReducerOutput<CoreSlice>> = either {
    val membership = ensureNotNull(clans.clanOf(command.agent)) { WorldRejection.NotInAnyClan(command.agent) }
    val clanId = membership.clan.id
    ensure(membership.clanRank.atLeast(ClanAction.INVITE.minRank)) {
        WorldRejection.InsufficientClanRank(command.agent, clanId.value, ClanAction.INVITE.minRank, membership.clanRank)
    }
    // Rejects inviting yourself too — you already belong to this clan.
    ensure(clans.clanOf(command.invitee) == null) {
        WorldRejection.InviteeAlreadyInClan(command.agent, command.invitee)
    }

    val pending = clanInvites.findByClan(clanId)
    val ttl = balance.clanInviteTtlSeconds()
    val ttlTicks = (ttl + tickIntervalSeconds - 1) / tickIntervalSeconds
    val expiresAtTick = tick + ttlTicks

    val existing = pending.firstOrNull { it.inviteeId == command.invitee }
    if (existing != null) {
        // Re-invite: refresh the TTL, don't double-count the cap, re-emit so the invitee sees it.
        val refreshed = existing.copy(sentAtTick = tick, expiresAtTick = expiresAtTick)
        clanInvites.create(refreshed, ttlSeconds = ttl)
        return@either ReducerOutput(core, events = listOf(inviteReceived(refreshed, command.commandId, tick)))
    }

    val cap = balance.baselineClanCapacity()
    val currentMembers = clans.memberCount(clanId)
    ensure(currentMembers + pending.size + 1 <= cap) {
        WorldRejection.ClanFull(command.agent, clanId.value, currentMembers, pending.size, cap)
    }

    val invite = ClanInvite(
        inviteId = ClanInviteId(UUID.randomUUID()),
        clanId = clanId,
        inviterId = command.agent,
        inviteeId = command.invitee,
        sentAtTick = tick,
        expiresAtTick = expiresAtTick,
    )
    clanInvites.create(invite, ttlSeconds = ttl)
    ReducerOutput(core, events = listOf(inviteReceived(invite, command.commandId, tick)))
}

fun reduceRespondClanInvite(
    core: CoreSlice,
    command: ClanCommand.RespondClanInvite,
    clans: ClanRegistry,
    clanInvites: ClanInviteStore,
    balance: BalanceLookup,
    tick: Long,
): Either<WorldRejection, ReducerOutput<CoreSlice>> = either {
    val invite = ensureNotNull(clanInvites.find(ClanInviteId(command.inviteId))) {
        WorldRejection.ClanInviteNotFound(command.agent, command.inviteId)
    }
    ensure(invite.inviteeId == command.agent) { WorldRejection.NotClanInvitee(command.agent, command.inviteId) }

    if (!command.accept) {
        clanInvites.delete(invite.inviteId)
        return@either ReducerOutput(
            core,
            events = listOf(
                ClanEvent.ClanInviteDeclined(
                    invite.clanId, invite.inviteId, invite.inviterId, invite.inviteeId,
                    listeners = setOf(invite.inviterId), tick = tick, causedBy = command.commandId,
                ),
            ),
        )
    }

    // Re-validate at accept time — context may have shifted since the invite was sent.
    if (clans.clanOf(invite.inviteeId) != null) {
        clanInvites.delete(invite.inviteId)
        raise(void(invite, WorldRejection.ClanInviteVoid.ClanInviteVoidReason.INVITEE_ALREADY_IN_CLAN))
    }
    ensureNotNull(clans.findClan(invite.clanId)) {
        clanInvites.delete(invite.inviteId)
        void(invite, WorldRejection.ClanInviteVoid.ClanInviteVoidReason.CLAN_DISSOLVED)
    }
    if (clans.memberCount(invite.clanId) >= balance.baselineClanCapacity()) {
        clanInvites.delete(invite.inviteId)
        raise(void(invite, WorldRejection.ClanInviteVoid.ClanInviteVoidReason.CLAN_FULL))
    }

    when (clans.addMember(invite.clanId, invite.inviteeId, ClanRank.INITIATE, tick)) {
        AddMemberOutcome.Added -> Unit
        is AddMemberOutcome.AlreadyInClan -> {
            clanInvites.delete(invite.inviteId)
            raise(void(invite, WorldRejection.ClanInviteVoid.ClanInviteVoidReason.INVITEE_ALREADY_IN_CLAN))
        }
        AddMemberOutcome.ClanNotFound -> {
            clanInvites.delete(invite.inviteId)
            raise(void(invite, WorldRejection.ClanInviteVoid.ClanInviteVoidReason.CLAN_DISSOLVED))
        }
    }
    clanInvites.delete(invite.inviteId)
    val members = clans.roster(invite.clanId).map { it.agentId }.toSet()
    ReducerOutput(
        core,
        events = listOf(
            ClanEvent.ClanJoined(invite.clanId, invite.inviteeId, ClanRank.INITIATE, members, tick, command.commandId),
        ),
    )
}

fun reduceKickClanMember(
    core: CoreSlice,
    command: ClanCommand.KickClanMember,
    clans: ClanRegistry,
    agents: AgentRegistry,
    tick: Long,
): Either<WorldRejection, ReducerOutput<CoreSlice>> = either {
    ensure(command.target != command.agent) { WorldRejection.CannotKickSelfFromClan(command.agent) }
    val membership = ensureNotNull(clans.clanOf(command.agent)) { WorldRejection.NotInAnyClan(command.agent) }
    val clanId = membership.clan.id
    ensure(membership.clanRank.atLeast(ClanAction.KICK.minRank)) {
        WorldRejection.InsufficientClanRank(command.agent, clanId.value, ClanAction.KICK.minRank, membership.clanRank)
    }
    val target = ensureNotNull(clans.clanOf(command.target).inSameClan(clanId)) {
        WorldRejection.TargetNotClanMember(command.agent, command.target, clanId.value)
    }
    ensure(target.clanRank.ordinal < membership.clanRank.ordinal) {
        rankAction(command.agent, command.target, clanId, WorldRejection.InvalidClanRankAction.InvalidClanRankActionReason.TARGET_NOT_BELOW_ACTOR)
    }
    val listeners = clans.roster(clanId).map { it.agentId }.toSet()
    clans.removeMember(clanId, command.target)
    // The kicked member leaves the faction with their clan membership; clear the slot-bonus mirror.
    if (membership.clan.factionId != null) agents.setFactionRank(command.target, null)
    ReducerOutput(
        core,
        events = listOf(ClanEvent.ClanLeft(clanId, command.target, ClanEvent.ClanLeft.Reason.KICKED, listeners, tick, command.commandId)),
    )
}

fun reducePromoteClanMember(
    core: CoreSlice,
    command: ClanCommand.PromoteClanMember,
    clans: ClanRegistry,
    tick: Long,
): Either<WorldRejection, ReducerOutput<CoreSlice>> = either {
    val membership = ensureNotNull(clans.clanOf(command.agent)) { WorldRejection.NotInAnyClan(command.agent) }
    val clanId = membership.clan.id
    ensure(membership.clanRank.atLeast(ClanAction.PROMOTE.minRank)) {
        WorldRejection.InsufficientClanRank(command.agent, clanId.value, ClanAction.PROMOTE.minRank, membership.clanRank)
    }
    val target = ensureNotNull(clans.clanOf(command.target).inSameClan(clanId)) {
        WorldRejection.TargetNotClanMember(command.agent, command.target, clanId.value)
    }
    val current = target.clanRank
    // Promote raises one rank but may never mint an Archon — that is transfer_clan_leadership.
    ensure(current.ordinal + 1 <= ClanRank.VANGUARD.ordinal) {
        rankAction(command.agent, command.target, clanId, WorldRejection.InvalidClanRankAction.InvalidClanRankActionReason.ALREADY_TOP_ASSIGNABLE)
    }
    val newRank = ClanRank.entries[current.ordinal + 1]
    ensure(newRank.ordinal < membership.clanRank.ordinal) {
        rankAction(command.agent, command.target, clanId, WorldRejection.InvalidClanRankAction.InvalidClanRankActionReason.TARGET_NOT_BELOW_ACTOR)
    }
    clans.changeClanRank(clanId, command.target, newRank)
    val listeners = clans.roster(clanId).map { it.agentId }.toSet()
    ReducerOutput(
        core,
        events = listOf(ClanEvent.RankChanged(clanId, command.target, current, newRank, listeners, tick, command.commandId)),
    )
}

fun reduceDemoteClanMember(
    core: CoreSlice,
    command: ClanCommand.DemoteClanMember,
    clans: ClanRegistry,
    tick: Long,
): Either<WorldRejection, ReducerOutput<CoreSlice>> = either {
    val membership = ensureNotNull(clans.clanOf(command.agent)) { WorldRejection.NotInAnyClan(command.agent) }
    val clanId = membership.clan.id
    ensure(membership.clanRank.atLeast(ClanAction.DEMOTE.minRank)) {
        WorldRejection.InsufficientClanRank(command.agent, clanId.value, ClanAction.DEMOTE.minRank, membership.clanRank)
    }
    val target = ensureNotNull(clans.clanOf(command.target).inSameClan(clanId)) {
        WorldRejection.TargetNotClanMember(command.agent, command.target, clanId.value)
    }
    val current = target.clanRank
    // Can't demote a peer/superior (or yourself); can't go below the floor.
    ensure(current.ordinal < membership.clanRank.ordinal) {
        rankAction(command.agent, command.target, clanId, WorldRejection.InvalidClanRankAction.InvalidClanRankActionReason.TARGET_NOT_BELOW_ACTOR)
    }
    ensure(current.ordinal - 1 >= ClanRank.INITIATE.ordinal) {
        rankAction(command.agent, command.target, clanId, WorldRejection.InvalidClanRankAction.InvalidClanRankActionReason.ALREADY_LOWEST)
    }
    val newRank = ClanRank.entries[current.ordinal - 1]
    clans.changeClanRank(clanId, command.target, newRank)
    val listeners = clans.roster(clanId).map { it.agentId }.toSet()
    ReducerOutput(
        core,
        events = listOf(ClanEvent.RankChanged(clanId, command.target, current, newRank, listeners, tick, command.commandId)),
    )
}

private fun inviteReceived(invite: ClanInvite, causedBy: UUID, tick: Long) =
    ClanEvent.ClanInviteReceived(
        clanId = invite.clanId,
        inviteId = invite.inviteId,
        inviter = invite.inviterId,
        invitee = invite.inviteeId,
        sentAtTick = invite.sentAtTick,
        expiresAtTick = invite.expiresAtTick,
        listeners = setOf(invite.inviteeId),
        tick = tick,
        causedBy = causedBy,
    )

private fun void(invite: ClanInvite, reason: WorldRejection.ClanInviteVoid.ClanInviteVoidReason) =
    WorldRejection.ClanInviteVoid(invite.inviteeId, invite.inviteId.value, reason)

private fun rankAction(
    actor: dev.gvart.genesara.player.AgentId,
    target: dev.gvart.genesara.player.AgentId,
    clanId: dev.gvart.genesara.world.ClanId,
    reason: WorldRejection.InvalidClanRankAction.InvalidClanRankActionReason,
) = WorldRejection.InvalidClanRankAction(actor, target, clanId.value, reason)

/** Returns the membership only when it resolves to [clanId]; null otherwise (wrong clan or no clan). */
private fun dev.gvart.genesara.world.ClanMembership?.inSameClan(clanId: dev.gvart.genesara.world.ClanId): dev.gvart.genesara.world.ClanMembership? =
    this?.takeIf { it.clan.id == clanId }
