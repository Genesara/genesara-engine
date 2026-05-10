package dev.gvart.genesara.world.internal.classes

import dev.gvart.genesara.account.PlayerId
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.player.AddCharacterXpOutcome
import dev.gvart.genesara.player.Agent
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.player.NoOpClassLookup
import dev.gvart.genesara.world.internal.testsupport.InMemoryBehaviorTracker
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CharacterXpProgressionTest {

    private val agentId = AgentId(UUID.randomUUID())
    private val tick = 7L

    @Test
    fun `routes a 9-to-10 transition through the level-10 emitter`() {
        val agents = SequencedRegistry(
            grant = AddCharacterXpOutcome.Granted(
                previousLevel = 9,
                currentLevel = 10,
                xpCurrent = 0,
                xpToNext = 1000,
                unspentAttributePoints = 50,
                cappedAtPendingClassChoice = false,
            ),
            // Agent is at level 10 with no class — emitter conditions met but the
            // catalog is empty (NoOpClassLookup) so the emitter aborts internally.
            // That's fine for this test: we only assert the orchestrator routed
            // through to it (recordCalls == 0 means we never reached the registry path,
            // which would happen on the no-op catalog's empty list).
            stateAfter = level10Unclassed(),
        )
        val l10 = RecordingL10Emitter(agents)
        val l50 = RecordingL50Emitter(agents)
        val progression = CharacterXpProgression(agents, l10, l50, FixedTickClock(tick))

        progression.grant(agentId, 100)

        assertEquals(1, l10.invocations)
        assertEquals(tick, l10.lastTick)
        assertEquals(0, l50.invocations)
    }

    @Test
    fun `does not invoke the emitter when the level did not transition into 10`() {
        val agents = SequencedRegistry(
            grant = AddCharacterXpOutcome.Granted(
                previousLevel = 5,
                currentLevel = 6,
                xpCurrent = 0,
                xpToNext = 600,
                unspentAttributePoints = 30,
                cappedAtPendingClassChoice = false,
            ),
            stateAfter = level10Unclassed().copy(level = 6),
        )
        val l10 = RecordingL10Emitter(agents)
        val l50 = RecordingL50Emitter(agents)
        val progression = CharacterXpProgression(agents, l10, l50, FixedTickClock(tick))

        progression.grant(agentId, 100)

        assertEquals(0, l10.invocations)
        assertEquals(0, l50.invocations)
    }

    @Test
    fun `does not invoke the emitter when transitioning past 10 from 10`() {
        val agents = SequencedRegistry(
            grant = AddCharacterXpOutcome.Granted(
                previousLevel = 10,
                currentLevel = 11,
                xpCurrent = 0,
                xpToNext = 1100,
                unspentAttributePoints = 55,
                cappedAtPendingClassChoice = false,
            ),
            stateAfter = level10Unclassed().copy(level = 11),
        )
        val l10 = RecordingL10Emitter(agents)
        val l50 = RecordingL50Emitter(agents)
        val progression = CharacterXpProgression(agents, l10, l50, FixedTickClock(tick))

        progression.grant(agentId, 1100)

        assertEquals(0, l10.invocations, "the offer fires only once, on the 9→10 boundary")
        assertEquals(0, l50.invocations)
    }

    @Test
    fun `routes a 49-to-50 transition through the level-50 emitter`() {
        val agents = SequencedRegistry(
            grant = AddCharacterXpOutcome.Granted(
                previousLevel = 49,
                currentLevel = 50,
                xpCurrent = 0,
                xpToNext = 5000,
                unspentAttributePoints = 250,
                cappedAtPendingClassChoice = false,
                cappedAtPendingEvolutionChoice = false,
            ),
            stateAfter = level10Unclassed().copy(level = 50),
        )
        val l10 = RecordingL10Emitter(agents)
        val l50 = RecordingL50Emitter(agents)
        val progression = CharacterXpProgression(agents, l10, l50, FixedTickClock(tick))

        progression.grant(agentId, 100)

        assertEquals(0, l10.invocations, "L10 emitter only fires on the 9→10 boundary")
        assertEquals(1, l50.invocations)
        assertEquals(tick, l50.lastTick)
    }

    @Test
    fun `passes through a NegativeDelta outcome without touching the emitters`() {
        val agents = SequencedRegistry(grant = AddCharacterXpOutcome.NegativeDelta, stateAfter = level10Unclassed())
        val l10 = RecordingL10Emitter(agents)
        val l50 = RecordingL50Emitter(agents)
        val progression = CharacterXpProgression(agents, l10, l50, FixedTickClock(tick))

        val outcome = progression.grant(agentId, -5)

        assertEquals(AddCharacterXpOutcome.NegativeDelta, outcome)
        assertEquals(0, l10.invocations)
        assertEquals(0, l50.invocations)
    }

    @Test
    fun `unknown agent (null) is propagated and the emitter is not invoked`() {
        val agents = SequencedRegistry(grant = null, stateAfter = level10Unclassed())
        val l10 = RecordingL10Emitter(agents)
        val l50 = RecordingL50Emitter(agents)
        val progression = CharacterXpProgression(agents, l10, l50, FixedTickClock(tick))

        val outcome = progression.grant(agentId, 100)

        assertNull(outcome)
        assertEquals(0, l10.invocations)
        assertEquals(0, l50.invocations)
    }

    private fun level10Unclassed() = Agent(
        id = agentId,
        owner = PlayerId(UUID.randomUUID()),
        name = "Hopeful",
        classId = null,
        level = 10,
    )

    private inner class RecordingL10Emitter(agents: AgentRegistry) : Level10ChoiceEmitter(
        agents = agents,
        classes = NoOpClassLookup,
        behavior = InMemoryBehaviorTracker(),
        publisher = SilentPublisher,
    ) {
        var invocations = 0
        var lastTick: Long = -1

        override fun tryEmitFor(agentId: AgentId, tick: Long) {
            invocations += 1
            lastTick = tick
        }
    }

    private inner class RecordingL50Emitter(agents: AgentRegistry) : Level50EvolutionEmitter(
        agents = agents,
        classes = NoOpClassLookup,
        behavior = InMemoryBehaviorTracker(),
        publisher = SilentPublisher,
    ) {
        var invocations = 0
        var lastTick: Long = -1

        override fun tryEmitFor(agentId: AgentId, tick: Long) {
            invocations += 1
            lastTick = tick
        }
    }

    private class SequencedRegistry(
        private val grant: AddCharacterXpOutcome?,
        private val stateAfter: Agent,
    ) : AgentRegistry {
        override fun find(id: AgentId): Agent? = stateAfter
        override fun listForOwner(owner: PlayerId): List<Agent> = listOf(stateAfter)
        override fun addCharacterXp(agentId: AgentId, delta: Int): AddCharacterXpOutcome? = grant
    }

    private object SilentPublisher : ApplicationEventPublisher {
        override fun publishEvent(event: Any) {
            // Discard — the test only cares whether tryEmitFor was reached.
            assertTrue(true)
        }
    }

    private class FixedTickClock(private val tick: Long) : TickClock {
        override fun currentTick(): Long = tick
    }
}
