package dev.gvart.genesara.world.clan.internal

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.player.FactionRank
import dev.gvart.genesara.world.ClanRank
import dev.gvart.genesara.world.ClanRegistry
import dev.gvart.genesara.world.CreateFactionOutcome
import dev.gvart.genesara.world.FactionInvite
import dev.gvart.genesara.world.FactionInviteId
import dev.gvart.genesara.world.FactionInviteStore
import dev.gvart.genesara.world.FactionRegistry
import dev.gvart.genesara.world.WorldRejection
import dev.gvart.genesara.world.commands.FactionCommand
import dev.gvart.genesara.world.events.FactionEvent
import dev.gvart.genesara.world.internal.balance.BalanceLookup
import dev.gvart.genesara.world.internal.worldstate.ReducerOutput
import dev.gvart.genesara.world.internal.worldstate.slices.CoreSlice
import java.util.UUID

/**
 * Faction-layer reducers (#22). Each orchestrates the clan-side [FactionRegistry] writes (faction
 * row, `clans.faction_id`, `clan_members.faction_rank`) and then mirrors every affected agent's
 * faction rank onto `:player` via [AgentRegistry.setFactionRank] — the bridge that activates the
 * skill-slot faction bonus. Single-threaded tick serializes these multi-store writes.
 */
fun reduceCreateFaction(
    core: CoreSlice,
    command: FactionCommand.CreateFaction,
    clans: ClanRegistry,
    factions: FactionRegistry,
    agents: AgentRegistry,
    tick: Long,
): Either<WorldRejection, ReducerOutput<CoreSlice>> = either {
    val membership = ensureNotNull(clans.clanOf(command.agent)) { WorldRejection.NotInAnyClan(command.agent) }
    val clanId = membership.clan.id
    ensure(membership.clanRank == ClanRank.ARCHON) { WorldRejection.NotClanArchon(command.agent, clanId.value) }
    ensure(membership.clan.factionId == null) { WorldRejection.AlreadyInFaction(command.agent, clanId.value) }

    when (val outcome = factions.createFaction(command.name, clanId, command.agent, tick)) {
        is CreateFactionOutcome.Created -> {
            outcome.memberRanks.forEach { (agentId, rank) -> agents.setFactionRank(agentId, rank) }
            ReducerOutput(
                core,
                events = listOf(
                    FactionEvent.FactionFormed(outcome.faction.id, clanId, outcome.memberRanks.keys, tick, command.commandId),
                ),
            )
        }

        CreateFactionOutcome.NameTaken -> raise(WorldRejection.FactionNameTaken(command.agent, command.name))
    }
}

fun reduceInviteClanToFaction(
    core: CoreSlice,
    command: FactionCommand.InviteClanToFaction,
    clans: ClanRegistry,
    factionInvites: FactionInviteStore,
    balance: BalanceLookup,
    tickIntervalSeconds: Long,
    tick: Long,
): Either<WorldRejection, ReducerOutput<CoreSlice>> = either {
    val membership = ensureNotNull(clans.clanOf(command.agent)) { WorldRejection.NotInAnyClan(command.agent) }
    val factionId = ensureNotNull(membership.clan.factionId) { WorldRejection.NotInAnyFaction(command.agent) }
    val actorRank = membership.factionRank ?: FactionRank.PACT
    ensure(actorRank.atLeast(FactionRank.PILLAR)) {
        WorldRejection.InsufficientFactionRank(command.agent, factionId.value, FactionRank.PILLAR, actorRank)
    }
    val targetClan = ensureNotNull(clans.findClan(command.targetClanId)) {
        WorldRejection.TargetClanNotFound(command.agent, command.targetClanId.value)
    }
    ensure(targetClan.factionId == null) {
        WorldRejection.TargetClanAlreadyInFaction(command.agent, command.targetClanId.value)
    }

    val ttl = balance.factionInviteTtlSeconds()
    val expiresAtTick = tick + (ttl + tickIntervalSeconds - 1) / tickIntervalSeconds
    val existing = factionInvites.findByTargetClan(command.targetClanId).firstOrNull { it.factionId == factionId }
    val invite = existing?.copy(sentAtTick = tick, expiresAtTick = expiresAtTick)
        ?: FactionInvite(FactionInviteId(UUID.randomUUID()), factionId, command.targetClanId, tick, expiresAtTick)
    factionInvites.create(invite, ttlSeconds = ttl)

    val targetArchons = clans.roster(command.targetClanId).filter { it.clanRank == ClanRank.ARCHON }.map { it.agentId }.toSet()
    ReducerOutput(
        core,
        events = listOf(
            FactionEvent.FactionInviteReceived(factionId, invite.inviteId, command.targetClanId, targetArchons, tick, command.commandId),
        ),
    )
}

fun reduceRespondFactionInvite(
    core: CoreSlice,
    command: FactionCommand.RespondFactionInvite,
    clans: ClanRegistry,
    factions: FactionRegistry,
    factionInvites: FactionInviteStore,
    agents: AgentRegistry,
    tick: Long,
): Either<WorldRejection, ReducerOutput<CoreSlice>> = either {
    val invite = ensureNotNull(factionInvites.find(FactionInviteId(command.inviteId))) {
        WorldRejection.FactionInviteNotFound(command.agent, command.inviteId)
    }
    val membership = ensureNotNull(clans.clanOf(command.agent)) { WorldRejection.NotInAnyClan(command.agent) }
    ensure(membership.clan.id == invite.targetClanId && membership.clanRank == ClanRank.ARCHON) {
        WorldRejection.NotFactionInvitee(command.agent, command.inviteId)
    }

    if (!command.accept) {
        factionInvites.delete(invite.inviteId)
        return@either ReducerOutput(core, events = emptyList())
    }

    // Re-validate: the target clan must not have joined a faction meanwhile, and the faction must still exist.
    if (membership.clan.factionId != null) {
        factionInvites.delete(invite.inviteId)
        raise(WorldRejection.FactionInviteVoid(command.agent, command.inviteId, WorldRejection.FactionInviteVoid.FactionInviteVoidReason.TARGET_CLAN_ALREADY_IN_FACTION))
    }
    ensureNotNull(factions.findFaction(invite.factionId)) {
        factionInvites.delete(invite.inviteId)
        WorldRejection.FactionInviteVoid(command.agent, command.inviteId, WorldRejection.FactionInviteVoid.FactionInviteVoidReason.FACTION_DISSOLVED)
    }

    val ranks = factions.joinFaction(invite.factionId, invite.targetClanId)
    ranks.forEach { (agentId, rank) -> agents.setFactionRank(agentId, rank) }
    factionInvites.delete(invite.inviteId)
    val listeners = factions.agentsInFaction(invite.factionId).toSet()
    ReducerOutput(
        core,
        events = listOf(FactionEvent.FactionJoined(invite.factionId, invite.targetClanId, listeners, tick, command.commandId)),
    )
}

fun reduceLeaveFaction(
    core: CoreSlice,
    command: FactionCommand.LeaveFaction,
    clans: ClanRegistry,
    factions: FactionRegistry,
    agents: AgentRegistry,
    tick: Long,
): Either<WorldRejection, ReducerOutput<CoreSlice>> = either {
    val membership = ensureNotNull(clans.clanOf(command.agent)) { WorldRejection.NotInAnyClan(command.agent) }
    val clanId = membership.clan.id
    ensure(membership.clanRank == ClanRank.ARCHON) { WorldRejection.NotClanArchon(command.agent, clanId.value) }
    ensureNotNull(membership.clan.factionId) { WorldRejection.NotInAnyFaction(command.agent) }

    val result = factions.leaveFaction(clanId)
    result.clearedAgents.forEach { agents.setFactionRank(it, null) }

    val events = mutableListOf<FactionEvent>()
    val remaining = factions.agentsInFaction(result.factionId).toSet()
    events += FactionEvent.FactionLeft(result.factionId, clanId, result.clearedAgents.toSet() + remaining, tick, command.commandId)
    if (result.factionEmptied) {
        factions.deleteFaction(result.factionId)
        events += FactionEvent.FactionDissolved(result.factionId, result.clearedAgents.toSet(), tick, command.commandId)
    }
    ReducerOutput(core, events = events)
}

fun reducePromoteFactionMember(
    core: CoreSlice,
    command: FactionCommand.PromoteFactionMember,
    clans: ClanRegistry,
    factions: FactionRegistry,
    agents: AgentRegistry,
    tick: Long,
): Either<WorldRejection, ReducerOutput<CoreSlice>> = either {
    val (factionId, current, target) = resolveFactionRankChange(command.agent, command.target, clans).bind()
    // Promote raises one rank but never mints a second Sovereign — Pillar is the ceiling here.
    ensure(current.ordinal + 1 <= FactionRank.PILLAR.ordinal) {
        WorldRejection.InvalidFactionRankAction(command.agent, command.target, factionId.value, WorldRejection.InvalidFactionRankAction.InvalidFactionRankActionReason.ALREADY_TOP_ASSIGNABLE)
    }
    val newRank = FactionRank.entries[current.ordinal + 1]
    applyFactionRank(command.target, newRank, factions, agents)
    ReducerOutput(
        core,
        events = listOf(FactionEvent.FactionRankChanged(factionId, command.target, current, newRank, factions.agentsInFaction(factionId).toSet(), tick, command.commandId)),
    )
}

fun reduceDemoteFactionMember(
    core: CoreSlice,
    command: FactionCommand.DemoteFactionMember,
    clans: ClanRegistry,
    factions: FactionRegistry,
    agents: AgentRegistry,
    tick: Long,
): Either<WorldRejection, ReducerOutput<CoreSlice>> = either {
    val (factionId, current, target) = resolveFactionRankChange(command.agent, command.target, clans).bind()
    ensure(current.ordinal - 1 >= FactionRank.PACT.ordinal) {
        WorldRejection.InvalidFactionRankAction(command.agent, command.target, factionId.value, WorldRejection.InvalidFactionRankAction.InvalidFactionRankActionReason.ALREADY_LOWEST)
    }
    val newRank = FactionRank.entries[current.ordinal - 1]
    applyFactionRank(command.target, newRank, factions, agents)
    ReducerOutput(
        core,
        events = listOf(FactionEvent.FactionRankChanged(factionId, command.target, current, newRank, factions.agentsInFaction(factionId).toSet(), tick, command.commandId)),
    )
}

private data class FactionRankChangeContext(
    val factionId: dev.gvart.genesara.world.FactionId,
    val targetCurrentRank: FactionRank,
    val target: dev.gvart.genesara.player.AgentId,
)

/** Shared Sovereign-only validation for faction promote/demote; binds the (faction, target rank) context. */
private fun resolveFactionRankChange(
    actor: dev.gvart.genesara.player.AgentId,
    target: dev.gvart.genesara.player.AgentId,
    clans: ClanRegistry,
): Either<WorldRejection, FactionRankChangeContext> = either {
    val membership = ensureNotNull(clans.clanOf(actor)) { WorldRejection.NotInAnyClan(actor) }
    val factionId = ensureNotNull(membership.clan.factionId) { WorldRejection.NotInAnyFaction(actor) }
    val actorRank = membership.factionRank ?: FactionRank.PACT
    ensure(actorRank == FactionRank.SOVEREIGN) {
        WorldRejection.InsufficientFactionRank(actor, factionId.value, FactionRank.SOVEREIGN, actorRank)
    }
    ensure(target != actor) {
        WorldRejection.InvalidFactionRankAction(actor, target, factionId.value, WorldRejection.InvalidFactionRankAction.InvalidFactionRankActionReason.CANNOT_TARGET_SELF)
    }
    val targetMembership = ensureNotNull(clans.clanOf(target)) {
        WorldRejection.FactionTargetNotMember(actor, target, factionId.value)
    }
    ensure(targetMembership.clan.factionId == factionId) {
        WorldRejection.FactionTargetNotMember(actor, target, factionId.value)
    }
    val current = ensureNotNull(targetMembership.factionRank) {
        WorldRejection.FactionTargetNotMember(actor, target, factionId.value)
    }
    FactionRankChangeContext(factionId, current, target)
}

private fun applyFactionRank(target: dev.gvart.genesara.player.AgentId, rank: FactionRank, factions: FactionRegistry, agents: AgentRegistry) {
    factions.setFactionRank(target, rank)
    agents.setFactionRank(target, rank)
}
