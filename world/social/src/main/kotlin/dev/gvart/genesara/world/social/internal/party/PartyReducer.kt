package dev.gvart.genesara.world.social.internal.party

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.world.BuildingStatus
import dev.gvart.genesara.world.BuildingsLookup
import dev.gvart.genesara.world.Party
import dev.gvart.genesara.world.PartyId
import dev.gvart.genesara.world.PartyInvite
import dev.gvart.genesara.world.PartyInviteId
import dev.gvart.genesara.world.PartyInviteStore
import dev.gvart.genesara.world.PartyMember
import dev.gvart.genesara.world.PartyStore
import dev.gvart.genesara.world.VisibleNodes
import dev.gvart.genesara.world.WorldRejection
import dev.gvart.genesara.world.commands.SocialCommand
import dev.gvart.genesara.world.events.SocialEvent
import dev.gvart.genesara.world.events.WorldEvent
import dev.gvart.genesara.world.internal.balance.BalanceLookup
import dev.gvart.genesara.world.internal.party.applyPartyLeave
import dev.gvart.genesara.world.internal.worldstate.ReducerOutput
import dev.gvart.genesara.world.internal.worldstate.slices.CoreSlice
import java.util.UUID

/**
 * Party reducers — Invite / Respond / Leave / Kick.
 *
 * Party state lives in Redis only (design Q11). The reducers carry no in-memory
 * delta — they take the [CoreSlice] for position reads and return it unchanged
 * via [ReducerOutput.sliceDelta]. State writes happen via [PartyStore] and
 * [PartyInviteStore] as side-effects inside the reducer body.
 *
 * Concurrency: the single-threaded `WorldTickHandler` reduces one command at a
 * time per world, so all Redis writes within a reducer body see no concurrent
 * mutation. Preconditions are validated up front (vision, party membership,
 * cap math) before any write to keep the partial-failure window narrow.
 */
fun reducePartyInvite(
    core: CoreSlice,
    command: SocialCommand.PartyInvite,
    balance: BalanceLookup,
    partyStore: PartyStore,
    partyInviteStore: PartyInviteStore,
    visibleNodes: VisibleNodes,
    agents: AgentRegistry,
    buildings: BuildingsLookup,
    tickIntervalSeconds: Long,
    tick: Long,
): Either<WorldRejection, ReducerOutput<CoreSlice>> = either {
    val inviterNode = ensureNotNull(core.positions[command.agent]) {
        WorldRejection.NotInWorld(command.agent)
    }
    ensure(command.invitees.isNotEmpty()) {
        WorldRejection.NonPositiveQuantity(command.agent, command.invitees.size)
    }

    val inviterParty = partyStore.findByAgent(command.agent)
    if (inviterParty != null) {
        ensure(inviterParty.leaderId == command.agent) {
            WorldRejection.NotPartyLeader(command.agent, inviterParty.partyId.value)
        }
    }

    val inviter = ensureNotNull(agents.find(command.agent)) {
        WorldRejection.UnknownProfile(command.agent)
    }
    val inviterBuildings = buildings.byNode(inviterNode)
        .filter { it.status == BuildingStatus.ACTIVE }
    val visibleSet = visibleNodes.visibleNodesFor(inviter, inviterNode, inviterBuildings)

    val pendingByInviter = partyInviteStore.findByInviter(command.agent)
    val alreadyPendingInvitees = pendingByInviter.map { it.inviteeId }.toSet()

    val newInvitees = mutableListOf<AgentId>()
    val refreshedInvites = mutableListOf<PartyInvite>()
    for (invitee in command.invitees.distinct()) {
        ensure(invitee != command.agent) { WorldRejection.CannotPartyWithSelf(command.agent) }

        // Refresh-path runs the full validation suite too: a void invite must surface
        // here rather than silently riding a stale TTL — agents can't tell vision /
        // membership shifted otherwise.
        val inviteeNode = ensureNotNull(core.positions[invitee]) {
            WorldRejection.InviteeNotInWorld(command.agent, invitee)
        }
        ensure(inviteeNode in visibleSet) {
            WorldRejection.InviteeNotInSight(command.agent, invitee, inviterNode, inviteeNode)
        }
        val inviteeParty = partyStore.findByAgent(invitee)
        ensure(inviteeParty == null) {
            WorldRejection.InviteeAlreadyInParty(command.agent, invitee)
        }

        val existing = pendingByInviter.firstOrNull { it.inviteeId == invitee }
        if (existing != null) {
            refreshedInvites += existing
            continue
        }
        newInvitees += invitee
    }

    val cap = balance.partyMaxSize()
    val currentMembers = inviterParty?.size ?: 1
    val pendingCount = alreadyPendingInvitees.size
    ensure(currentMembers + pendingCount + newInvitees.size <= cap) {
        WorldRejection.PartyCapacityExceeded(
            inviter = command.agent,
            currentMembers = currentMembers,
            pendingInvites = pendingCount,
            requested = newInvitees.size,
            cap = cap,
        )
    }

    val ttl = balance.partyInviteTtlSeconds()
    // TTL is wall-clock seconds (Redis EXPIRE is authoritative). Convert to ticks
    // for the agent-facing field so `expiresAtTick` is comparable to the engine
    // tick counter. Rounded up so a fractional tick window doesn't shrink to zero
    // when `ttl < tickIntervalSeconds`.
    val ttlTicks = (ttl + tickIntervalSeconds - 1) / tickIntervalSeconds
    val expiresAtTick = tick + ttlTicks
    val events = mutableListOf<WorldEvent>()
    for (existing in refreshedInvites) {
        partyInviteStore.create(existing, ttlSeconds = ttl)
    }
    for (invitee in newInvitees) {
        val invite = PartyInvite(
            inviteId = PartyInviteId(UUID.randomUUID()),
            inviterId = command.agent,
            inviteeId = invitee,
            sentAtTick = tick,
            expiresAtTick = expiresAtTick,
        )
        partyInviteStore.create(invite, ttlSeconds = ttl)
        events += SocialEvent.PartyInviteReceived(
            inviteId = invite.inviteId,
            inviter = invite.inviterId,
            invitee = invite.inviteeId,
            sentAtTick = invite.sentAtTick,
            expiresAtTick = invite.expiresAtTick,
            listeners = setOf(invite.inviteeId),
            tick = tick,
            causedBy = command.commandId,
        )
    }

    ReducerOutput(sliceDelta = core, events = events.toList())
}

fun reducePartyRespond(
    core: CoreSlice,
    command: SocialCommand.PartyRespond,
    balance: BalanceLookup,
    partyStore: PartyStore,
    partyInviteStore: PartyInviteStore,
    tick: Long,
): Either<WorldRejection, ReducerOutput<CoreSlice>> = either {
    val invite = ensureNotNull(partyInviteStore.find(PartyInviteId(command.inviteId))) {
        WorldRejection.PartyInviteNotFound(command.agent, command.inviteId)
    }
    ensure(invite.inviteeId == command.agent) {
        WorldRejection.NotPartyInvitee(command.agent, command.inviteId)
    }

    if (!command.accept) {
        partyInviteStore.delete(invite.inviteId)
        return@either ReducerOutput(
            sliceDelta = core,
            events = listOf(
                SocialEvent.PartyInviteDeclined(
                    inviteId = invite.inviteId,
                    inviter = invite.inviterId,
                    invitee = invite.inviteeId,
                    listeners = setOf(invite.inviterId),
                    tick = tick,
                    causedBy = command.commandId,
                ),
            ),
        )
    }

    val inviteeParty = partyStore.findByAgent(invite.inviteeId)
    if (inviteeParty != null) {
        partyInviteStore.delete(invite.inviteId)
        raise(
            WorldRejection.PartyInviteVoid(
                invitee = invite.inviteeId,
                inviteId = command.inviteId,
                reason = WorldRejection.PartyInviteVoid.PartyInviteVoidReason.INVITEE_ALREADY_IN_PARTY,
            )
        )
    }

    val inviterParty = partyStore.findByAgent(invite.inviterId)
    val (party, joinedAt) = if (inviterParty == null) {
        val freshId = PartyId(UUID.randomUUID())
        val fresh = Party(
            partyId = freshId,
            leaderId = invite.inviterId,
            members = listOf(
                PartyMember(invite.inviterId, joinedAtTick = tick),
                PartyMember(invite.inviteeId, joinedAtTick = tick),
            ),
            formedAtTick = tick,
        )
        partyStore.create(fresh)
        partyInviteStore.delete(invite.inviteId)
        fresh to tick
    } else {
        if (inviterParty.leaderId != invite.inviterId) {
            partyInviteStore.delete(invite.inviteId)
            raise(
                WorldRejection.PartyInviteVoid(
                    invitee = invite.inviteeId,
                    inviteId = command.inviteId,
                    reason = WorldRejection.PartyInviteVoid.PartyInviteVoidReason.INVITER_NOT_LEADER,
                )
            )
        }
        if (inviterParty.size >= balance.partyMaxSize()) {
            partyInviteStore.delete(invite.inviteId)
            raise(
                WorldRejection.PartyInviteVoid(
                    invitee = invite.inviteeId,
                    inviteId = command.inviteId,
                    reason = WorldRejection.PartyInviteVoid.PartyInviteVoidReason.PARTY_FULL,
                )
            )
        }
        val updated = ensureNotNull(
            partyStore.addMember(inviterParty.partyId, PartyMember(invite.inviteeId, joinedAtTick = tick))
        ) {
            // Should not happen — we just read the party row and we are the only writer for
            // this agent's command queue; if addMember returns null the party vanished
            // concurrently (e.g. last member kicked via a different queue). Surface as void.
            WorldRejection.PartyInviteVoid(
                invitee = invite.inviteeId,
                inviteId = command.inviteId,
                reason = WorldRejection.PartyInviteVoid.PartyInviteVoidReason.INVITER_LEFT_PARTY,
            )
        }
        partyInviteStore.delete(invite.inviteId)
        updated to tick
    }

    ReducerOutput(
        sliceDelta = core,
        events = listOf(
            SocialEvent.PartyJoined(
                partyId = party.partyId,
                joiner = invite.inviteeId,
                leader = party.leaderId,
                members = party.members,
                listeners = party.memberIds(),
                tick = joinedAt,
                causedBy = command.commandId,
            )
        ),
    )
}

fun reduceLeaveParty(
    core: CoreSlice,
    command: SocialCommand.LeaveParty,
    partyStore: PartyStore,
    partyInviteStore: PartyInviteStore,
    tick: Long,
): Either<WorldRejection, ReducerOutput<CoreSlice>> = either {
    val party = ensureNotNull(partyStore.findByAgent(command.agent)) {
        WorldRejection.NotInAnyParty(command.agent)
    }
    val events = applyPartyLeave(
        party = party,
        leaver = command.agent,
        reason = SocialEvent.PartyLeft.Reason.LEFT,
        partyStore = partyStore,
        partyInviteStore = partyInviteStore,
        causedBy = command.commandId,
        tick = tick,
    )
    ReducerOutput(sliceDelta = core, events = events)
}

fun reduceKickPartyMember(
    core: CoreSlice,
    command: SocialCommand.KickPartyMember,
    partyStore: PartyStore,
    partyInviteStore: PartyInviteStore,
    tick: Long,
): Either<WorldRejection, ReducerOutput<CoreSlice>> = either {
    ensure(command.agent != command.target) { WorldRejection.CannotKickSelf(command.agent) }

    val party = ensureNotNull(partyStore.findByAgent(command.agent)) {
        WorldRejection.NotInAnyParty(command.agent)
    }
    ensure(party.leaderId == command.agent) {
        WorldRejection.NotPartyLeader(command.agent, party.partyId.value)
    }
    ensure(party.contains(command.target)) {
        WorldRejection.KickTargetNotPartyMember(command.agent, command.target, party.partyId.value)
    }

    val events = applyPartyLeave(
        party = party,
        leaver = command.target,
        reason = SocialEvent.PartyLeft.Reason.KICKED,
        partyStore = partyStore,
        partyInviteStore = partyInviteStore,
        causedBy = command.commandId,
        tick = tick,
    )
    ReducerOutput(sliceDelta = core, events = events)
}

