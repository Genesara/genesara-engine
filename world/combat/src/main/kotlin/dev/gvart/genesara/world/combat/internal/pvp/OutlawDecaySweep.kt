package dev.gvart.genesara.world.combat.internal.pvp

import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.world.events.SocialEvent
import dev.gvart.genesara.world.events.WorldEvent
import dev.gvart.genesara.world.internal.balance.BalanceLookup
import org.springframework.stereotype.Component

/**
 * Mechanics-reference §11 outlaw decay. Runs on a coarse beat — every
 * [BalanceLookup.outlawDecayPeriodTicks] ticks the sweep subtracts
 * [BalanceLookup.outlawDecayPerPeriod] from every misconduct-flagged agent's
 * score, downshifting [dev.gvart.genesara.player.OutlawState] when the
 * resulting score crosses a bucket boundary downward.
 *
 * The registry returns one [dev.gvart.genesara.player.MisconductOutcome]
 * per affected row; the sweep filters to transitions and converts them
 * into [SocialEvent.OutlawStateChanged] events for the dispatcher to fan
 * out. `causedBy` is null — no single command caused the decay.
 *
 * Patterned after `MountMaintenanceSweep`: same period-gate shape, same
 * tick-loop integration point in `WorldTickHandler`.
 */
@Component
class OutlawDecaySweep(
    private val agents: AgentRegistry,
    private val balance: BalanceLookup,
) {

    fun sweep(tick: Long): List<WorldEvent> {
        val period = balance.outlawDecayPeriodTicks().coerceAtLeast(1)
        if (tick % period != 0L) return emptyList()
        val amount = balance.outlawDecayPerPeriod().coerceAtLeast(0)
        if (amount == 0) return emptyList()
        val outcomes = agents.decayMisconductScores(
            amount = amount,
            watchedAt = balance.outlawWatchedScore(),
            outlawAt = balance.outlawOutlawScore(),
        )
        return outcomes.filter { it.didTransition }.map { outcome ->
            SocialEvent.OutlawStateChanged(
                agent = outcome.agentId,
                previousState = outcome.oldState,
                newState = outcome.newState,
                score = outcome.newScore,
                listeners = setOf(outcome.agentId),
                tick = tick,
                causedBy = null,
            )
        }
    }
}
