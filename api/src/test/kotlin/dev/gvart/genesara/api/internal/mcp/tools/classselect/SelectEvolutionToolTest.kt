package dev.gvart.genesara.api.internal.mcp.tools.classselect

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityRegistry
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.player.Agent
import dev.gvart.genesara.player.AgentClass
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.player.AssignEvolutionOutcome
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

class SelectEvolutionToolTest {

    private val agent = AgentId(UUID.randomUUID())
    private val clock = MutableTestClock(Instant.parse("2026-01-01T00:00:00Z"))
    private val activity = AgentActivityRegistry(clock)
    private val tickClock = StubTickClock(currentTick = 50L)
    private val toolContext = ToolContext(emptyMap())

    @BeforeEach fun setUp() = AgentContextHolder.set(agent)
    @AfterEach fun tearDown() = AgentContextHolder.clear()

    @Test
    fun `commits the chosen evolution and emits ClassEvolved at the current tick`() {
        val agents = StubAgentRegistry(
            assignResult = AssignEvolutionOutcome.Assigned(
                from = AgentClass.SOLDIER,
                to = AgentClass.HEAVY_SOLDIER,
            ),
        )
        val publisher = RecordingPublisher()
        val tool = SelectEvolutionTool(agents, tickClock, publisher, activity)

        val response = tool.invoke(classId = AgentClass.HEAVY_SOLDIER, toolContext = toolContext)

        assertEquals("ok", response.kind)
        assertEquals(AgentClass.HEAVY_SOLDIER, response.classId)
        assertEquals(AgentClass.SOLDIER, response.from)
        val event = publisher.events.filterIsInstance<AgentEvent.ClassEvolved>().single()
        assertEquals(agent, event.agent)
        assertEquals(AgentClass.SOLDIER, event.fromClass)
        assertEquals(AgentClass.HEAVY_SOLDIER, event.toClass)
        assertEquals(50L, event.tick)
    }

    @Test
    fun `rejects when no evolution offer is pending`() {
        val agents = StubAgentRegistry(assignResult = AssignEvolutionOutcome.NoPendingOffer)
        val publisher = RecordingPublisher()
        val tool = SelectEvolutionTool(agents, tickClock, publisher, activity)

        val response = tool.invoke(classId = AgentClass.HEAVY_SOLDIER, toolContext = toolContext)

        assertEquals("rejected", response.kind)
        assertEquals("no_pending_offer", response.reason)
        assertTrue(publisher.events.none { it is AgentEvent.ClassEvolved })
    }

    @Test
    fun `rejects when the chosen class is not in the offer and surfaces the pending pair`() {
        val pending = ClassOffer(AgentClass.HEAVY_SOLDIER, AgentClass.STEALTH_SOLDIER)
        val agents = StubAgentRegistry(assignResult = AssignEvolutionOutcome.NotInOffer(pending))
        val publisher = RecordingPublisher()
        val tool = SelectEvolutionTool(agents, tickClock, publisher, activity)

        val response = tool.invoke(classId = AgentClass.COMMANDER, toolContext = toolContext)

        assertEquals("not_offered", response.reason)
        val detail = response.detail!!
        assertTrue(detail.contains("HEAVY_SOLDIER"))
        assertTrue(detail.contains("STEALTH_SOLDIER"))
    }

    @Test
    fun `rejects when the agent has no class assigned`() {
        val agents = StubAgentRegistry(assignResult = AssignEvolutionOutcome.NoClassAssigned)
        val tool = SelectEvolutionTool(agents, tickClock, RecordingPublisher(), activity)

        val response = tool.invoke(classId = AgentClass.HEAVY_SOLDIER, toolContext = toolContext)

        assertEquals("no_class_assigned", response.reason)
    }

    @Test
    fun `rejects when the agent already evolved`() {
        val agents = StubAgentRegistry(
            assignResult = AssignEvolutionOutcome.AlreadyEvolved(AgentClass.STEALTH_SOLDIER),
        )
        val publisher = RecordingPublisher()
        val tool = SelectEvolutionTool(agents, tickClock, publisher, activity)

        val response = tool.invoke(classId = AgentClass.HEAVY_SOLDIER, toolContext = toolContext)

        assertEquals("already_evolved", response.reason)
        assertTrue(response.detail!!.contains("STEALTH_SOLDIER"))
    }

    @Test
    fun `rejects when the agent row is missing`() {
        val agents = StubAgentRegistry(assignResult = AssignEvolutionOutcome.UnknownAgent)
        val tool = SelectEvolutionTool(agents, tickClock, RecordingPublisher(), activity)

        val response = tool.invoke(classId = AgentClass.HEAVY_SOLDIER, toolContext = toolContext)

        assertEquals("unknown_agent", response.reason)
    }

    @Test
    fun `rejects when the chosen class belongs to a different parent tree`() {
        val agents = StubAgentRegistry(
            assignResult = AssignEvolutionOutcome.WrongParent(
                expectedParent = AgentClass.SOLDIER,
                actualParent = AgentClass.SCOUT,
            ),
        )
        val tool = SelectEvolutionTool(agents, tickClock, RecordingPublisher(), activity)

        val response = tool.invoke(classId = AgentClass.RANGER, toolContext = toolContext)

        assertEquals("wrong_parent", response.reason)
        val detail = response.detail!!
        assertTrue(detail.contains("SCOUT"))
        assertTrue(detail.contains("SOLDIER"))
    }

    private class StubAgentRegistry(
        private val assignResult: AssignEvolutionOutcome,
    ) : AgentRegistry {
        override fun find(id: AgentId): Agent? = Agent(id = id, owner = PlayerId(UUID.randomUUID()), name = "stub")
        override fun listForOwner(owner: PlayerId): List<Agent> = emptyList()
        override fun assignEvolution(agentId: AgentId, evolutionId: AgentClass): AssignEvolutionOutcome =
            assignResult
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
