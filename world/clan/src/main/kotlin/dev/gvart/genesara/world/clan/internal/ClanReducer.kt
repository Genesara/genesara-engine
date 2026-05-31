package dev.gvart.genesara.world.clan.internal

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import dev.gvart.genesara.world.ClanRank
import dev.gvart.genesara.world.ClanRegistry
import dev.gvart.genesara.world.CreateClanOutcome
import dev.gvart.genesara.world.WorldRejection
import dev.gvart.genesara.world.commands.ClanCommand
import dev.gvart.genesara.world.events.ClanEvent
import dev.gvart.genesara.world.internal.worldstate.ReducerOutput
import dev.gvart.genesara.world.internal.worldstate.slices.CoreSlice

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
    tick: Long,
): Either<WorldRejection, ReducerOutput<CoreSlice>> = either {
    val membership = ensureNotNull(clans.clanOf(command.agent)) { WorldRejection.NotInAnyClan(command.agent) }
    val clanId = membership.clan.id

    if (membership.clanRank == ClanRank.ARCHON) {
        // Sole Archon must hand off before leaving; an Archon who is the last member
        // dissolves the clan by leaving (#22 succession rule).
        ensure(clans.memberCount(clanId) <= 1) { WorldRejection.MustHandOffLeadership(command.agent, clanId.value) }
        val former = clans.dissolve(clanId)
        ReducerOutput(
            sliceDelta = core,
            events = listOf(ClanEvent.ClanDissolved(clanId, former.toSet(), tick, command.commandId)),
        )
    } else {
        val listeners = clans.roster(clanId).map { it.agentId }.toSet()
        clans.removeMember(clanId, command.agent)
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
    tick: Long,
): Either<WorldRejection, ReducerOutput<CoreSlice>> = either {
    val membership = ensureNotNull(clans.clanOf(command.agent)) { WorldRejection.NotInAnyClan(command.agent) }
    val clanId = membership.clan.id
    ensure(membership.clanRank == ClanRank.ARCHON) { WorldRejection.NotClanArchon(command.agent, clanId.value) }
    val former = clans.dissolve(clanId)
    ReducerOutput(
        sliceDelta = core,
        events = listOf(ClanEvent.ClanDissolved(clanId, former.toSet(), tick, command.commandId)),
    )
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
