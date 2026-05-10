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
import dev.gvart.genesara.player.RecordClassOfferOutcome
import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.player.events.AgentEvent
import dev.gvart.genesara.world.internal.behavior.ActionCategory
import dev.gvart.genesara.world.internal.testsupport.InMemoryBehaviorTracker
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class Level10ChoiceEmitterTest {

    private val agentId = AgentId(UUID.randomUUID())
    private val owner = PlayerId(UUID.randomUUID())
    private val tick = 42L

    @Test
    fun `emits ClassChoiceOffered with the top-2 candidates and persists the offer`() {
        val behavior = InMemoryBehaviorTracker().also {
            repeat(20) { _ -> it.record(agentId, ActionCategory.COMBAT, tick) }
            repeat(5) { _ -> it.record(agentId, ActionCategory.GATHER, tick) }
        }
        val classes = StubClassLookup(
            classFor(AgentClass.SOLDIER, mapOf("COMBAT" to 1.0)),
            classFor(AgentClass.HUNTER, mapOf("COMBAT" to 0.6, "GATHER" to 0.4)),
            classFor(AgentClass.ARTISAN, mapOf("CRAFT" to 1.0)),
        )
        val agents = StubAgentRegistry(initial = level10Unclassed())
        val publisher = RecordingPublisher()
        val emitter = Level10ChoiceEmitter(agents, classes, behavior, publisher)

        emitter.tryEmitFor(agentId, tick)

        val offer = ClassOffer(AgentClass.SOLDIER, AgentClass.HUNTER)
        assertEquals(offer, agents.recordedOffer)
        val event = publisher.events.filterIsInstance<AgentEvent.ClassChoiceOffered>().single()
        assertEquals(agentId, event.agent)
        assertEquals(listOf(AgentClass.SOLDIER, AgentClass.HUNTER), event.candidates)
        assertEquals(tick, event.tick)
    }

    @Test
    fun `is a no-op when the agent is below level 10`() {
        val agents = StubAgentRegistry(initial = level10Unclassed().copy(level = 9))
        val publisher = RecordingPublisher()
        val emitter = Level10ChoiceEmitter(agents, NoOpClassLookup, InMemoryBehaviorTracker(), publisher)

        emitter.tryEmitFor(agentId, tick)

        assertTrue(publisher.events.isEmpty())
        assertEquals(0, agents.recordCalls)
    }

    @Test
    fun `is a no-op when the agent already has a class`() {
        val agents = StubAgentRegistry(initial = level10Unclassed().copy(classId = AgentClass.SOLDIER))
        val publisher = RecordingPublisher()
        val emitter = Level10ChoiceEmitter(agents, NoOpClassLookup, InMemoryBehaviorTracker(), publisher)

        emitter.tryEmitFor(agentId, tick)

        assertTrue(publisher.events.isEmpty())
        assertEquals(0, agents.recordCalls)
    }

    @Test
    fun `is a no-op when an offer is already pending`() {
        val agents = StubAgentRegistry(
            initial = level10Unclassed().copy(offeredClasses = ClassOffer(AgentClass.SOLDIER, AgentClass.SCOUT)),
        )
        val publisher = RecordingPublisher()
        val emitter = Level10ChoiceEmitter(agents, NoOpClassLookup, InMemoryBehaviorTracker(), publisher)

        emitter.tryEmitFor(agentId, tick)

        assertTrue(publisher.events.isEmpty())
        assertEquals(0, agents.recordCalls)
    }

    @Test
    fun `does not publish when the catalog returned fewer than two candidates`() {
        val classes = StubClassLookup(classFor(AgentClass.SOLDIER, mapOf("COMBAT" to 1.0)))
        val agents = StubAgentRegistry(initial = level10Unclassed())
        val publisher = RecordingPublisher()
        val emitter = Level10ChoiceEmitter(agents, classes, InMemoryBehaviorTracker(), publisher)

        emitter.tryEmitFor(agentId, tick)

        assertTrue(publisher.events.isEmpty())
        assertEquals(0, agents.recordCalls, "no DB write when scoring is unsafe")
    }

    @Test
    fun `does not publish when the registry rejects the offer at the DB layer`() {
        val behavior = InMemoryBehaviorTracker().also {
            it.record(agentId, ActionCategory.COMBAT, tick)
        }
        val classes = StubClassLookup(
            classFor(AgentClass.SOLDIER, mapOf("COMBAT" to 1.0)),
            classFor(AgentClass.HUNTER, mapOf("COMBAT" to 0.5)),
        )
        val agents = StubAgentRegistry(
            initial = level10Unclassed(),
            recordResult = RecordClassOfferOutcome.AlreadyClassed,
        )
        val publisher = RecordingPublisher()
        val emitter = Level10ChoiceEmitter(agents, classes, behavior, publisher)

        emitter.tryEmitFor(agentId, tick)

        assertEquals(1, agents.recordCalls)
        assertTrue(
            publisher.events.none { it is AgentEvent.ClassChoiceOffered },
            "registry rejection suppresses the event",
        )
    }

    private fun level10Unclassed() = Agent(
        id = agentId,
        owner = owner,
        name = "Hopeful",
        classId = null,
        level = 10,
        offeredClasses = null,
    )

    private fun classFor(id: AgentClass, fingerprint: Map<String, Double>) = ClassDefinition(
        id = id,
        displayName = id.name,
        description = "",
        sightRange = 3,
        primarySkills = emptySet<SkillId>(),
        neutralSkills = emptySet(),
        forbiddenCombatSkills = emptySet(),
        damageMultipliers = emptyMap(),
        behaviorFingerprint = fingerprint,
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
        private val recordResult: RecordClassOfferOutcome = RecordClassOfferOutcome.Recorded,
    ) : AgentRegistry {
        private var current: Agent = initial
        var recordedOffer: ClassOffer? = null
        var recordCalls = 0

        override fun find(id: AgentId): Agent? = if (id == current.id) current else null
        override fun listForOwner(owner: PlayerId): List<Agent> = listOf(current)
        override fun recordPendingClassChoice(agentId: AgentId, offer: ClassOffer): RecordClassOfferOutcome {
            recordCalls += 1
            if (recordResult == RecordClassOfferOutcome.Recorded) recordedOffer = offer
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
