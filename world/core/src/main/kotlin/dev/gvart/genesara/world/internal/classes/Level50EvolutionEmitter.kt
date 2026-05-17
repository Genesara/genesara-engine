package dev.gvart.genesara.world.internal.classes

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.player.ClassLookup
import dev.gvart.genesara.player.ClassOffer
import dev.gvart.genesara.player.RecordEvolutionOfferOutcome
import dev.gvart.genesara.player.classes.ClassFingerprintScorer
import dev.gvart.genesara.player.events.AgentEvent
import dev.gvart.genesara.world.internal.behavior.BehaviorTracker
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component

/**
 * Mirror of [Level10ChoiceEmitter] for the L50 evolution event (#34). Reads
 * the agent's *windowed* behavior snapshot (counters since `select_class`
 * committed — see `mechanics-reference.md` §4.1), scores it against the
 * parent class's evolution branches, persists the top-2 as the agent's
 * pending evolution offer, and publishes [AgentEvent.EvolutionChoiceOffered].
 *
 * Idempotency lives in [AgentRegistry.recordPendingEvolutionChoice]: the DB
 * write rejects when the agent has no class, is already on an evolution, or
 * already has a pending evolution offer. A duplicate fire is a no-op for
 * both persistence and the event.
 */
@Component
class Level50EvolutionEmitter(
    private val agents: AgentRegistry,
    private val classes: ClassLookup,
    private val behavior: BehaviorTracker,
    private val publisher: ApplicationEventPublisher,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun tryEmitFor(agentId: AgentId, tick: Long) {
        val agent = agents.find(agentId)
        if (agent == null) {
            log.warn("L50 evolution emit skipped for {}: agent row missing", agentId.id)
            return
        }
        val classId = agent.classId ?: return
        if (agent.offeredEvolutions != null) return
        if (agent.level < LEVEL_FIFTY_THRESHOLD) return

        val def = classes.byId(classId) ?: run {
            log.warn("L50 evolution emit skipped for {}: catalog has no entry for {}", agentId.id, classId)
            return
        }
        if (def.parentClass != null) return

        val candidates = classes.evolutionsOf(classId)
        if (candidates.size < 2) {
            log.warn(
                "L50 evolution emit aborted for {}: catalog produced {} evolution candidate(s) for {}",
                agentId.id, candidates.size, classId,
            )
            return
        }
        val snapshot = behavior.snapshotForWindow(agentId).mapKeys { it.key.name }
        val topTwo = ClassFingerprintScorer.scoreTopTwo(snapshot, candidates)
        if (topTwo.size < 2) {
            log.warn(
                "L50 evolution emit aborted for {}: scorer returned {} candidate(s)",
                agentId.id, topTwo.size,
            )
            return
        }
        val offer = ClassOffer(topTwo[0], topTwo[1])

        when (val outcome = agents.recordPendingEvolutionChoice(agentId, offer)) {
            RecordEvolutionOfferOutcome.Recorded ->
                publisher.publishEvent(
                    AgentEvent.EvolutionChoiceOffered(
                        agent = agentId,
                        fromClass = classId,
                        candidates = offer.toList(),
                        tick = tick,
                    )
                )

            RecordEvolutionOfferOutcome.NoClassAssigned,
            is RecordEvolutionOfferOutcome.AlreadyEvolved,
            is RecordEvolutionOfferOutcome.AlreadyOffered,
            RecordEvolutionOfferOutcome.UnknownAgent ->
                log.debug("L50 evolution emit skipped for {}: {}", agentId.id, outcome)

            is RecordEvolutionOfferOutcome.InvalidCandidate ->
                log.error(
                    "L50 evolution emit produced an invalid candidate for {}: {} is not an evolution of {}",
                    agentId.id, outcome.candidate, outcome.expectedParent,
                )
        }
    }

    private companion object {
        const val LEVEL_FIFTY_THRESHOLD = 50
    }
}
