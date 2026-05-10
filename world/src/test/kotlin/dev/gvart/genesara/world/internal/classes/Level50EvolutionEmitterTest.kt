package dev.gvart.genesara.world.internal.classes

import dev.gvart.genesara.account.PlayerId
import dev.gvart.genesara.player.Agent
import dev.gvart.genesara.player.AgentClass
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.player.ClassDefinition
import dev.gvart.genesara.player.ClassLookup
import dev.gvart.genesara.player.ClassOffer
import dev.gvart.genesara.player.NoOpClassLookup
import dev.gvart.genesara.player.RecordEvolutionOfferOutcome
import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.player.events.AgentEvent
import dev.gvart.genesara.world.internal.behavior.ActionCategory
import dev.gvart.genesara.world.internal.testsupport.InMemoryBehaviorTracker
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Level50EvolutionEmitterTest {

    private val agentId = AgentId(UUID.randomUUID())
    private val owner = PlayerId(UUID.randomUUID())
    private val tick = 9_000L

    @Test
    fun `emits EvolutionChoiceOffered with the windowed top-2 evolutions of the parent`() {
        val behavior = InMemoryBehaviorTracker().also {
            // Pre-class history that should NOT count once we mark the baseline.
            repeat(50) { _ -> it.record(agentId, ActionCategory.GATHER, tick) }
            it.markBaseline(agentId)
            // Post-class window: 20 COMBAT + 5 EXPLORE — should beat HEAVY_SOLDIER's
            // tank fingerprint and pick STEALTH_SOLDIER over COMMANDER.
            repeat(20) { _ -> it.record(agentId, ActionCategory.COMBAT, tick) }
            repeat(5) { _ -> it.record(agentId, ActionCategory.EXPLORE, tick) }
        }
        val classes = StubClassLookup(
            soldierWith(
                evolutions = listOf(
                    AgentClass.HEAVY_SOLDIER,
                    AgentClass.STEALTH_SOLDIER,
                    AgentClass.COMMANDER,
                ),
            ),
            evolutionFor(AgentClass.HEAVY_SOLDIER, AgentClass.SOLDIER, mapOf("COMBAT" to 1.4)),
            evolutionFor(AgentClass.STEALTH_SOLDIER, AgentClass.SOLDIER, mapOf("COMBAT" to 1.2, "EXPLORE" to 0.5)),
            evolutionFor(AgentClass.COMMANDER, AgentClass.SOLDIER, mapOf("COMBAT" to 1.0, "SOCIAL" to 0.8)),
        )
        val agents = StubAgentRegistry(initial = soldierAt(level = 50))
        val publisher = RecordingPublisher()
        val emitter = Level50EvolutionEmitter(agents, classes, behavior, publisher)

        emitter.tryEmitFor(agentId, tick)

        val event = publisher.events.filterIsInstance<AgentEvent.EvolutionChoiceOffered>().single()
        assertEquals(agentId, event.agent)
        assertEquals(AgentClass.SOLDIER, event.fromClass)
        assertEquals(listOf(AgentClass.HEAVY_SOLDIER, AgentClass.STEALTH_SOLDIER), event.candidates)
        assertEquals(tick, event.tick)
        assertEquals(ClassOffer(AgentClass.HEAVY_SOLDIER, AgentClass.STEALTH_SOLDIER), agents.recordedOffer)
    }

    @Test
    fun `is a no-op when the agent is below level 50`() {
        val classes = StubClassLookup(
            soldierWith(evolutions = listOf(AgentClass.HEAVY_SOLDIER, AgentClass.STEALTH_SOLDIER)),
            evolutionFor(AgentClass.HEAVY_SOLDIER, AgentClass.SOLDIER, mapOf("COMBAT" to 1.0)),
            evolutionFor(AgentClass.STEALTH_SOLDIER, AgentClass.SOLDIER, mapOf("COMBAT" to 0.9)),
        )
        val agents = StubAgentRegistry(initial = soldierAt(level = 49))
        val publisher = RecordingPublisher()
        val emitter = Level50EvolutionEmitter(agents, classes, InMemoryBehaviorTracker(), publisher)

        emitter.tryEmitFor(agentId, tick)

        assertEquals(0, agents.recordCalls)
        assertTrue(publisher.events.isEmpty())
    }

    @Test
    fun `is a no-op when the agent has no class assigned`() {
        val agents = StubAgentRegistry(initial = soldierAt(level = 50).copy(classId = null))
        val publisher = RecordingPublisher()
        val emitter = Level50EvolutionEmitter(agents, NoOpClassLookup, InMemoryBehaviorTracker(), publisher)

        emitter.tryEmitFor(agentId, tick)

        assertEquals(0, agents.recordCalls)
        assertTrue(publisher.events.isEmpty())
    }

    @Test
    fun `is a no-op when the agent is already on an evolution class`() {
        val classes = StubClassLookup(
            evolutionFor(AgentClass.HEAVY_SOLDIER, AgentClass.SOLDIER, mapOf("COMBAT" to 1.0)),
        )
        val agents = StubAgentRegistry(initial = soldierAt(level = 50).copy(classId = AgentClass.HEAVY_SOLDIER))
        val publisher = RecordingPublisher()
        val emitter = Level50EvolutionEmitter(agents, classes, InMemoryBehaviorTracker(), publisher)

        emitter.tryEmitFor(agentId, tick)

        assertEquals(0, agents.recordCalls)
        assertTrue(publisher.events.isEmpty())
    }

    @Test
    fun `is a no-op when an evolution offer is already pending`() {
        val classes = StubClassLookup(
            soldierWith(evolutions = listOf(AgentClass.HEAVY_SOLDIER, AgentClass.STEALTH_SOLDIER)),
            evolutionFor(AgentClass.HEAVY_SOLDIER, AgentClass.SOLDIER, mapOf("COMBAT" to 1.0)),
            evolutionFor(AgentClass.STEALTH_SOLDIER, AgentClass.SOLDIER, mapOf("COMBAT" to 0.9)),
        )
        val agents = StubAgentRegistry(
            initial = soldierAt(level = 50).copy(
                offeredEvolutions = ClassOffer(AgentClass.HEAVY_SOLDIER, AgentClass.STEALTH_SOLDIER),
            ),
        )
        val publisher = RecordingPublisher()
        val emitter = Level50EvolutionEmitter(agents, classes, InMemoryBehaviorTracker(), publisher)

        emitter.tryEmitFor(agentId, tick)

        assertEquals(0, agents.recordCalls)
        assertTrue(publisher.events.isEmpty())
    }

    @Test
    fun `aborts when the parent class declares fewer than 2 evolutions`() {
        val classes = StubClassLookup(
            soldierWith(evolutions = listOf(AgentClass.HEAVY_SOLDIER)),
            evolutionFor(AgentClass.HEAVY_SOLDIER, AgentClass.SOLDIER, mapOf("COMBAT" to 1.0)),
        )
        val agents = StubAgentRegistry(initial = soldierAt(level = 50))
        val publisher = RecordingPublisher()
        val emitter = Level50EvolutionEmitter(agents, classes, InMemoryBehaviorTracker(), publisher)

        emitter.tryEmitFor(agentId, tick)

        assertEquals(0, agents.recordCalls)
        assertTrue(publisher.events.isEmpty())
    }

    @Test
    fun `suppresses the event when the registry rejects the offer write`() {
        val classes = StubClassLookup(
            soldierWith(evolutions = listOf(AgentClass.HEAVY_SOLDIER, AgentClass.STEALTH_SOLDIER)),
            evolutionFor(AgentClass.HEAVY_SOLDIER, AgentClass.SOLDIER, mapOf("COMBAT" to 1.0)),
            evolutionFor(AgentClass.STEALTH_SOLDIER, AgentClass.SOLDIER, mapOf("COMBAT" to 0.9)),
        )
        val agents = StubAgentRegistry(
            initial = soldierAt(level = 50),
            recordResult = RecordEvolutionOfferOutcome.AlreadyEvolved(AgentClass.HEAVY_SOLDIER),
        )
        val publisher = RecordingPublisher()
        val emitter = Level50EvolutionEmitter(agents, classes, InMemoryBehaviorTracker(), publisher)

        emitter.tryEmitFor(agentId, tick)

        assertEquals(1, agents.recordCalls)
        assertTrue(publisher.events.none { it is AgentEvent.EvolutionChoiceOffered })
    }

    private fun soldierAt(level: Int) = Agent(
        id = agentId,
        owner = owner,
        name = "Veteran",
        classId = AgentClass.SOLDIER,
        level = level,
        offeredClasses = null,
        offeredEvolutions = null,
    )

    private fun soldierWith(evolutions: List<AgentClass>) = ClassDefinition(
        id = AgentClass.SOLDIER,
        displayName = "Soldier",
        description = "",
        sightRange = 3,
        primarySkills = emptySet<SkillId>(),
        neutralSkills = emptySet(),
        forbiddenCombatSkills = emptySet(),
        damageMultipliers = emptyMap(),
        behaviorFingerprint = mapOf("COMBAT" to 1.0),
        parentClass = null,
        evolutions = evolutions,
    )

    private fun evolutionFor(id: AgentClass, parent: AgentClass, fingerprint: Map<String, Double>) =
        ClassDefinition(
            id = id,
            displayName = id.name,
            description = "",
            sightRange = 3,
            primarySkills = emptySet(),
            neutralSkills = emptySet(),
            forbiddenCombatSkills = emptySet(),
            damageMultipliers = emptyMap(),
            behaviorFingerprint = fingerprint,
            parentClass = parent,
        )

    private class StubClassLookup(vararg defs: ClassDefinition) : ClassLookup {
        private val list = defs.toList()
        override fun byId(classId: AgentClass): ClassDefinition? = list.firstOrNull { it.id == classId }
        override fun all(): List<ClassDefinition> = list
        override fun baseClasses(): List<ClassDefinition> = list.filter { it.parentClass == null }
        override fun evolutionsOf(parent: AgentClass): List<ClassDefinition> {
            val def = byId(parent) ?: return emptyList()
            return def.evolutions.mapNotNull(::byId)
        }
        override fun sightRange(classId: AgentClass?): Int = 3
        override fun skillXpMultiplier(classId: AgentClass?, skill: SkillId): Double = 1.0
        override fun damageMultiplier(classId: AgentClass?, damageType: String): Double = 1.0
        override fun forbidsCombatSkill(classId: AgentClass?, combatSkill: SkillId): Boolean = false
    }

    private class StubAgentRegistry(
        initial: Agent,
        private val recordResult: RecordEvolutionOfferOutcome = RecordEvolutionOfferOutcome.Recorded,
    ) : AgentRegistry {
        private var current: Agent = initial
        var recordedOffer: ClassOffer? = null
        var recordCalls = 0

        override fun find(id: AgentId): Agent? = if (id == current.id) current else null
        override fun listForOwner(owner: PlayerId): List<Agent> = listOf(current)
        override fun recordPendingEvolutionChoice(
            agentId: AgentId,
            offer: ClassOffer,
        ): RecordEvolutionOfferOutcome {
            recordCalls += 1
            if (recordResult == RecordEvolutionOfferOutcome.Recorded) recordedOffer = offer
            return recordResult
        }
    }

    private class RecordingPublisher : ApplicationEventPublisher {
        val events = mutableListOf<Any>()
        override fun publishEvent(event: Any) {
            events += event
        }
    }
}
