package dev.gvart.genesara.world.internal.classes

import dev.gvart.genesara.account.PlayerId
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.player.AddCharacterXpOutcome
import dev.gvart.genesara.player.Agent
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.player.CharacterXpSource
import dev.gvart.genesara.player.NoOpClassLookup
import dev.gvart.genesara.player.events.AgentEvent
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
    private val commandId = UUID.randomUUID()

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
            stateAfter = level10Unclassed(),
        )
        val l10 = RecordingL10Emitter(agents)
        val l50 = RecordingL50Emitter(agents)
        val progression = DefaultCharacterXpProgression(agents, l10, l50, FixedTickClock(tick), RecordingPublisher())

        progression.grant(agentId, CharacterXpSource.HARVEST, delta = 100, commandId = commandId)

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
        val progression = DefaultCharacterXpProgression(agents, l10, l50, FixedTickClock(tick), RecordingPublisher())

        progression.grant(agentId, CharacterXpSource.HARVEST, delta = 100, commandId = commandId)

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
        val progression = DefaultCharacterXpProgression(agents, l10, l50, FixedTickClock(tick), RecordingPublisher())

        progression.grant(agentId, CharacterXpSource.HARVEST, delta = 1100, commandId = commandId)

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
        val progression = DefaultCharacterXpProgression(agents, l10, l50, FixedTickClock(tick), RecordingPublisher())

        progression.grant(agentId, CharacterXpSource.HARVEST, delta = 100, commandId = commandId)

        assertEquals(0, l10.invocations, "L10 emitter only fires on the 9→10 boundary")
        assertEquals(1, l50.invocations)
        assertEquals(tick, l50.lastTick)
    }

    @Test
    fun `passes through a NegativeDelta outcome without touching the emitters`() {
        val agents = SequencedRegistry(grant = AddCharacterXpOutcome.NegativeDelta, stateAfter = level10Unclassed())
        val l10 = RecordingL10Emitter(agents)
        val l50 = RecordingL50Emitter(agents)
        val publisher = RecordingPublisher()
        val progression = DefaultCharacterXpProgression(agents, l10, l50, FixedTickClock(tick), publisher)

        val outcome = progression.grant(agentId, CharacterXpSource.HARVEST, delta = -5, commandId = commandId)

        assertEquals(AddCharacterXpOutcome.NegativeDelta, outcome)
        assertEquals(0, l10.invocations)
        assertEquals(0, l50.invocations)
        assertTrue(publisher.published.isEmpty(), "NegativeDelta must not publish CharacterXpGained")
    }

    @Test
    fun `unknown agent (null) is propagated and the emitter is not invoked`() {
        val agents = SequencedRegistry(grant = null, stateAfter = level10Unclassed())
        val l10 = RecordingL10Emitter(agents)
        val l50 = RecordingL50Emitter(agents)
        val publisher = RecordingPublisher()
        val progression = DefaultCharacterXpProgression(agents, l10, l50, FixedTickClock(tick), publisher)

        val outcome = progression.grant(agentId, CharacterXpSource.HARVEST, delta = 100, commandId = commandId)

        assertNull(outcome)
        assertEquals(0, l10.invocations)
        assertEquals(0, l50.invocations)
        assertTrue(publisher.published.isEmpty())
    }

    @Test
    fun `publishes CharacterXpGained with source, amount, total, toNext, level, causedBy on Granted`() {
        val agents = SequencedRegistry(
            grant = AddCharacterXpOutcome.Granted(
                previousLevel = 1,
                currentLevel = 1,
                xpCurrent = 17,
                xpToNext = 100,
                unspentAttributePoints = 0,
                cappedAtPendingClassChoice = false,
            ),
            stateAfter = level10Unclassed().copy(level = 1),
        )
        val publisher = RecordingPublisher()
        val progression = DefaultCharacterXpProgression(
            agents,
            RecordingL10Emitter(agents),
            RecordingL50Emitter(agents),
            FixedTickClock(tick),
            publisher,
        )

        progression.grant(agentId, CharacterXpSource.HARVEST, delta = 1, commandId = commandId)

        val gained = publisher.published.single() as AgentEvent.CharacterXpGained
        assertEquals(agentId, gained.agent)
        assertEquals(CharacterXpSource.HARVEST, gained.source)
        assertEquals(1, gained.amount)
        assertEquals(17, gained.total)
        assertEquals(100, gained.toNext)
        assertEquals(1, gained.level)
        assertEquals(0, gained.unspentAttributePoints)
        assertEquals(tick, gained.tick)
        assertEquals(commandId, gained.causedBy)
    }

    @Test
    fun `publishes AgentLeveled after CharacterXpGained when the cascade crossed a level boundary`() {
        val agents = SequencedRegistry(
            grant = AddCharacterXpOutcome.Granted(
                previousLevel = 3,
                currentLevel = 5,
                xpCurrent = 10,
                xpToNext = 500,
                unspentAttributePoints = 25,
                cappedAtPendingClassChoice = false,
            ),
            stateAfter = level10Unclassed().copy(level = 5),
        )
        val publisher = RecordingPublisher()
        val progression = DefaultCharacterXpProgression(
            agents,
            RecordingL10Emitter(agents),
            RecordingL50Emitter(agents),
            FixedTickClock(tick),
            publisher,
        )

        progression.grant(agentId, CharacterXpSource.CONSUME, delta = 600, commandId = commandId)

        val (gained, leveled) = publisher.published.let { it[0] to it[1] }
        gained as AgentEvent.CharacterXpGained
        leveled as AgentEvent.AgentLeveled
        assertEquals(CharacterXpSource.CONSUME, gained.source)
        assertEquals(5, gained.level)
        assertEquals(agentId, leveled.agent)
        assertEquals(3, leveled.fromLevel)
        assertEquals(5, leveled.toLevel)
        assertEquals(25, leveled.unspentAttributePoints)
        assertEquals(tick, leveled.tick)
        assertEquals(commandId, leveled.causedBy)
    }

    @Test
    fun `does not publish AgentLeveled when the grant did not cross a level boundary`() {
        val agents = SequencedRegistry(
            grant = AddCharacterXpOutcome.Granted(
                previousLevel = 2,
                currentLevel = 2,
                xpCurrent = 50,
                xpToNext = 200,
                unspentAttributePoints = 5,
                cappedAtPendingClassChoice = false,
            ),
            stateAfter = level10Unclassed().copy(level = 2),
        )
        val publisher = RecordingPublisher()
        val progression = DefaultCharacterXpProgression(
            agents,
            RecordingL10Emitter(agents),
            RecordingL50Emitter(agents),
            FixedTickClock(tick),
            publisher,
        )

        progression.grant(agentId, CharacterXpSource.HARVEST, delta = 5, commandId = commandId)

        assertEquals(1, publisher.published.size, "only CharacterXpGained fires; level did not change")
        assertTrue(publisher.published.single() is AgentEvent.CharacterXpGained)
    }

    @Test
    fun `publishes CharacterXpGained even when the cascade is capped at the level-10 boundary`() {
        val agents = SequencedRegistry(
            grant = AddCharacterXpOutcome.Granted(
                previousLevel = 9,
                currentLevel = 10,
                xpCurrent = 1000,
                xpToNext = 1000,
                unspentAttributePoints = 50,
                cappedAtPendingClassChoice = true,
            ),
            stateAfter = level10Unclassed(),
        )
        val publisher = RecordingPublisher()
        val progression = DefaultCharacterXpProgression(
            agents,
            RecordingL10Emitter(agents),
            RecordingL50Emitter(agents),
            FixedTickClock(tick),
            publisher,
        )

        progression.grant(agentId, CharacterXpSource.HARVEST, delta = 200, commandId = commandId)

        val types = publisher.published.map { it::class.simpleName }
        assertEquals(listOf("CharacterXpGained", "AgentLeveled"), types)
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
            assertTrue(true)
        }
    }

    private class RecordingPublisher : ApplicationEventPublisher {
        val published = mutableListOf<Any>()
        override fun publishEvent(event: Any) {
            published += event
        }
    }

    private class FixedTickClock(private val tick: Long) : TickClock {
        override fun currentTick(): Long = tick
    }
}
