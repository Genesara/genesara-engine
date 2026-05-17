package dev.gvart.genesara.world.internal.classes

import dev.gvart.genesara.player.events.AgentEvent
import dev.gvart.genesara.world.internal.behavior.BehaviorTracker
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * Snapshots the agent's cumulative action counters into the per-row baseline
 * once the agent has committed a class via `select_class`. The L50 evolution
 * scorer (#34) reads `snapshotForWindow` which subtracts this baseline, so the
 * score sees only the agent's post-class behaviour. See
 * `mechanics-reference.md` §4.1 for the windowing rationale.
 *
 * Runs in [TransactionPhase.AFTER_COMMIT] so a `markBaseline` failure does NOT
 * roll back the original `select_class` write — the agent stays classed, the
 * baseline write retries on the next event or stays at zero. Counter logic
 * tolerates a missing baseline as "every action since L1 counts in the
 * window," which is a degraded but non-broken score; the explicit log here
 * surfaces the gap so a sweep can fix it post-incident.
 */
@Component
internal class BehaviorBaselineListener(
    private val behavior: BehaviorTracker,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun on(event: AgentEvent.ClassChosen) {
        try {
            behavior.markBaseline(event.agent)
        } catch (e: RuntimeException) {
            log.warn(
                "markBaseline failed for {} after select_class commit; L50 window will include pre-class actions until reseeded",
                event.agent.id, e,
            )
        }
    }
}
