package dev.gvart.genesara.api.internal.mcp.tools.events

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.events.AgentEvent
import dev.gvart.genesara.api.internal.mcp.events.AgentEventLog
import dev.gvart.genesara.api.internal.mcp.events.FakeAgentEventLog
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityRegistry
import dev.gvart.genesara.player.AgentId
import java.time.Clock
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.model.ToolContext
import tools.jackson.databind.node.JsonNodeFactory

class GetEventsToolTest {

    private val agentId = AgentId(UUID.randomUUID())
    private val log: FakeAgentEventLog = FakeAgentEventLog()
    private val activity = AgentActivityRegistry(Clock.systemUTC())
    private val tool = GetEventsTool(log, activity)
    private val toolContext = ToolContext(emptyMap())
    private val nodes = JsonNodeFactory.instance

    @BeforeEach
    fun setup() {
        AgentContextHolder.set(agentId)
    }

    @AfterEach
    fun teardown() {
        AgentContextHolder.clear()
    }

    private fun appendEvent(type: String, tick: Long = 1L): AgentEvent =
        log.append(agentId, type, tick, nodes.objectNode())

    @Test
    fun `returns up to limit events from the tail when since is null`() {
        repeat(5) { appendEvent("agent.moved", it.toLong()) }

        val output = tool.invoke(since = null, limit = 3, types = null, toolContext = toolContext)

        assertEquals(3, output.count)
        assertEquals(3, output.events.size)
    }

    @Test
    fun `returns events strictly after since`() {
        val e1 = appendEvent("agent.spawned")
        appendEvent("agent.moved")
        appendEvent("agent.moved")

        val output = tool.invoke(since = e1.seq, limit = 50, types = null, toolContext = toolContext)

        assertEquals(2, output.count)
        assertTrue(output.events.all { it.seq > e1.seq })
    }

    @Test
    fun `filters by type when types is provided`() {
        appendEvent("agent.spawned")
        appendEvent("agent.moved")
        appendEvent("party.invite_received")
        appendEvent("agent.moved")

        val output = tool.invoke(since = null, limit = 50, types = listOf("agent.moved"), toolContext = toolContext)

        assertEquals(2, output.count)
        assertTrue(output.events.all { it.type == "agent.moved" })
    }

    @Test
    fun `returns empty list when no matching types`() {
        appendEvent("agent.spawned")
        appendEvent("agent.moved")

        val output = tool.invoke(since = null, limit = 50, types = listOf("party.invite_received"), toolContext = toolContext)

        assertEquals(0, output.count)
        assertTrue(output.events.isEmpty())
    }

    @Test
    fun `caps limit at 200`() {
        repeat(300) { appendEvent("agent.moved", it.toLong()) }

        val output = tool.invoke(since = null, limit = 999, types = null, toolContext = toolContext)

        assertEquals(200, output.count)
    }

    @Test
    fun `returns all events when since is 0 and limit is large enough`() {
        repeat(5) { appendEvent("agent.moved", it.toLong()) }

        val output = tool.invoke(since = 0L, limit = 50, types = null, toolContext = toolContext)

        assertEquals(5, output.count)
    }

    @Test
    fun `events are returned in ascending seq order`() {
        repeat(5) { appendEvent("agent.moved", it.toLong()) }

        val output = tool.invoke(since = null, limit = 50, types = null, toolContext = toolContext)

        val seqs = output.events.map { it.seq }
        assertEquals(seqs.sorted(), seqs)
    }

    @Test
    fun `since plus types filter combine correctly`() {
        val first = appendEvent("party.invite_received")
        appendEvent("agent.moved")
        appendEvent("party.invite_received")

        val output = tool.invoke(since = first.seq, limit = 50, types = listOf("party.invite_received"), toolContext = toolContext)

        assertEquals(1, output.count)
        assertEquals("party.invite_received", output.events.single().type)
        assertTrue(output.events.single().seq > first.seq)
    }
}
