package dev.gvart.genesara.api.internal.mcp.tools.classselect

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityRegistry
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.player.Agent
import dev.gvart.genesara.player.AgentClass
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.player.AssignClassOutcome
import dev.gvart.genesara.player.ClassOffer
import dev.gvart.genesara.player.events.AgentEvent
import dev.gvart.genesara.account.PlayerId
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.model.ToolContext
import org.springframework.context.ApplicationEventPublisher
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SelectClassToolTest {

    private val agent = AgentId(UUID.randomUUID())
    private val clock = MutableTestClock(Instant.parse("2026-01-01T00:00:00Z"))
    private val activity = AgentActivityRegistry(clock)
    private val tickClock = StubTickClock(currentTick = 9L)
    private val toolContext = ToolContext(emptyMap())

    @BeforeEach fun setUp() = AgentContextHolder.set(agent)
    @AfterEach fun tearDown() = AgentContextHolder.clear()

    @Test
    fun `commits the chosen class and emits ClassChosen at the current tick`() {
        val agents = StubAgentRegistry(assignResult = AssignClassOutcome.Assigned)
        val publisher = RecordingPublisher()
        val tool = SelectClassTool(agents, tickClock, publisher, activity)

        val response = tool.invoke(classId = "SOLDIER", toolContext = toolContext)

        assertEquals("ok", response.kind)
        assertEquals("SOLDIER", response.classId)
        val recorded = agents.assignCalls.single()
        assertEquals(agent to AgentClass.SOLDIER, recorded)
        val event = publisher.events.filterIsInstance<AgentEvent.ClassChosen>().single()
        assertEquals(agent, event.agent)
        assertEquals(AgentClass.SOLDIER, event.classId)
        assertEquals(9L, event.tick)
    }

    @Test
    fun `rejects an unknown class id before touching the registry`() {
        val agents = StubAgentRegistry()
        val publisher = RecordingPublisher()
        val tool = SelectClassTool(agents, tickClock, publisher, activity)

        val response = tool.invoke(classId = "DRAGONLORD", toolContext = toolContext)

        assertEquals("rejected", response.kind)
        assertEquals("unknown_class", response.reason)
        assertTrue(agents.assignCalls.isEmpty())
        assertTrue(publisher.events.isEmpty())
    }

    @Test
    fun `rejects when no offer is pending`() {
        val agents = StubAgentRegistry(assignResult = AssignClassOutcome.NoPendingOffer)
        val publisher = RecordingPublisher()
        val tool = SelectClassTool(agents, tickClock, publisher, activity)

        val response = tool.invoke(classId = "SOLDIER", toolContext = toolContext)

        assertEquals("rejected", response.kind)
        assertEquals("no_pending_offer", response.reason)
        assertTrue(publisher.events.none { it is AgentEvent.ClassChosen })
    }

    @Test
    fun `rejects when the chosen class is not in the offer and surfaces the pending pair`() {
        val pending = ClassOffer(AgentClass.SOLDIER, AgentClass.SCOUT)
        val agents = StubAgentRegistry(assignResult = AssignClassOutcome.NotInOffer(pending))
        val publisher = RecordingPublisher()
        val tool = SelectClassTool(agents, tickClock, publisher, activity)

        val response = tool.invoke(classId = "RESEARCHER", toolContext = toolContext)

        assertEquals("not_offered", response.reason)
        val detail = response.detail!!
        assertTrue(detail.contains("SOLDIER"))
        assertTrue(detail.contains("SCOUT"))
    }

    @Test
    fun `rejects when the agent already has a class`() {
        val agents = StubAgentRegistry(assignResult = AssignClassOutcome.AlreadyClassed(AgentClass.MEDIC))
        val publisher = RecordingPublisher()
        val tool = SelectClassTool(agents, tickClock, publisher, activity)

        val response = tool.invoke(classId = "SOLDIER", toolContext = toolContext)

        assertEquals("already_classed", response.reason)
        assertTrue(response.detail!!.contains("MEDIC"))
    }

    @Test
    fun `rejects when the agent row is missing`() {
        val agents = StubAgentRegistry(assignResult = AssignClassOutcome.UnknownAgent)
        val tool = SelectClassTool(agents, tickClock, RecordingPublisher(), activity)

        val response = tool.invoke(classId = "SOLDIER", toolContext = toolContext)

        assertEquals("unknown_agent", response.reason)
    }

    private class StubAgentRegistry(
        private val assignResult: AssignClassOutcome = AssignClassOutcome.Assigned,
    ) : AgentRegistry {
        val assignCalls = mutableListOf<Pair<AgentId, AgentClass>>()

        override fun find(id: AgentId): Agent? = Agent(id = id, owner = PlayerId(UUID.randomUUID()), name = "stub")
        override fun listForOwner(owner: PlayerId): List<Agent> = emptyList()
        override fun assignClass(agentId: AgentId, classId: AgentClass): AssignClassOutcome {
            assignCalls += agentId to classId
            return assignResult
        }
    }

    private class RecordingPublisher : ApplicationEventPublisher {
        val events = mutableListOf<Any>()
        override fun publishEvent(event: Any) {
            events += event
        }
    }

    private class StubTickClock(private val currentTick: Long) : TickClock {
        override fun currentTick(): Long = currentTick
    }

    private class MutableTestClock(private var now: Instant) : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
    }
}
