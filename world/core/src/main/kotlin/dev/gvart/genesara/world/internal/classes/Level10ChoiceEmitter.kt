package dev.gvart.genesara.world.internal.classes

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.player.ClassLookup
import dev.gvart.genesara.player.ClassOffer
import dev.gvart.genesara.player.RecordClassOfferOutcome
import dev.gvart.genesara.player.classes.ClassFingerprintScorer
import dev.gvart.genesara.player.events.AgentEvent
import dev.gvart.genesara.world.internal.behavior.BehaviorTracker
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component

/**
 * Reads the per-agent behavior snapshot, scores it against the class catalog,
 * persists the resulting top-2 as the agent's pending class offer, and
 * publishes [AgentEvent.ClassChoiceOffered].
 *
 * Idempotency lives in [AgentRegistry.recordPendingClassChoice]: the DB write
 * rejects when the agent is already classed or already has a pending offer,
 * so a duplicate fire is a no-op for both the persistence and the event.
 *
 * TODO(#35 psionics): when psionic classes land, filter the catalog by
 * `ClassDefinition.psionicEntry` against the agent's attribute thresholds
 * before scoring. The field is deferred per #94, so v1 candidates are the
 * full 8-class catalog.
 */
@Component
class Level10ChoiceEmitter(
    private val agents: AgentRegistry,
    private val classes: ClassLookup,
    private val behavior: BehaviorTracker,
    private val publisher: ApplicationEventPublisher,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun tryEmitFor(agentId: AgentId, tick: Long) {
        val agent = agents.find(agentId)
        if (agent == null) {
            log.warn("Level-10 emit skipped for {}: agent row missing", agentId.id)
            return
        }
        if (agent.classId != null) return
        if (agent.offeredClasses != null) return
        if (agent.level < LEVEL_TEN_THRESHOLD) return

        val snapshot = behavior.snapshotFor(agentId).mapKeys { it.key.name }
        val topTwo = ClassFingerprintScorer.scoreTopTwo(snapshot, classes.baseClasses())
        if (topTwo.size < 2) {
            log.warn(
                "Level-10 emitter aborted for {}: catalog produced {} candidates",
                agentId.id, topTwo.size,
            )
            return
        }
        val offer = ClassOffer(topTwo[0], topTwo[1])

        when (val outcome = agents.recordPendingClassChoice(agentId, offer)) {
            RecordClassOfferOutcome.Recorded ->
                publisher.publishEvent(AgentEvent.ClassChoiceOffered(agentId, offer.toList(), tick))

            RecordClassOfferOutcome.AlreadyClassed,
            is RecordClassOfferOutcome.AlreadyOffered,
            RecordClassOfferOutcome.UnknownAgent ->
                log.debug("Level-10 emit skipped for {}: {}", agentId.id, outcome)
        }
    }

    private companion object {
        const val LEVEL_TEN_THRESHOLD = 10
    }
}
