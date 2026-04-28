package dev.gvart.genesara.api.internal.mcp.events

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.player.AgentId
import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.spec.McpSchema.ReadResourceRequest
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.mock
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.util.UUID
import kotlin.test.assertEquals

class AgentEventResourceTest {

    private val mapper = JsonMapper.builder().addModule(kotlinModule()).build()
    private val agent = AgentId(UUID.randomUUID())
    private val exchange = mock(McpSyncServerExchange::class.java)

    @AfterEach
    fun clearContext() {
        AgentContextHolder.clear()
    }

    @Test
    fun `read returns all events for the agent and is non-destructive`() {
        val log = FakeAgentEventLog()
        val event = log.append(
            agent,
            "agent.moved",
            tick = 7L,
            payload = mapper.createObjectNode().put("from", 1).put("to", 2),
        )

        AgentContextHolder.set(agent)
        val resource = AgentEventResource(log, mapper)
        val uri = "agent://${agent.id}/events"
        val result = resource.read(exchange, ReadResourceRequest(uri))

        val content = result.contents().single() as TextResourceContents
        assertEquals(uri, content.uri())
        assertEquals("application/json", content.mimeType())

        val parsed = mapper.readTree(content.text())
        assertEquals(1, parsed.size())
        val first = parsed.get(0)
        assertEquals(event.id.toString(), first.get("id").asString())
        assertEquals("agent.moved", first.get("type").asString())
        assertEquals(7L, first.get("tick").asLong())
        assertEquals(1L, first.get("seq").asLong())

        // Non-destructive: a follow-up read returns the same event.
        assertEquals(1, log.since(agent, 0).size)
    }

    @Test
    fun `read with after cursor returns only events past that seq`() {
        val log = FakeAgentEventLog()
        val first = log.append(agent, "agent.moved", 1L, mapper.createObjectNode())
        log.append(agent, "agent.moved", 2L, mapper.createObjectNode())

        AgentContextHolder.set(agent)
        val resource = AgentEventResource(log, mapper)
        val result = resource.read(
            exchange,
            ReadResourceRequest("agent://${agent.id}/events?after=${first.seq}"),
        )

        val parsed = mapper.readTree((result.contents().single() as TextResourceContents).text())
        assertEquals(1, parsed.size())
        assertEquals(2L, parsed.get(0).get("seq").asLong())
    }

    @Test
    fun `read without cursor defaults to after=0 returning everything`() {
        val log = FakeAgentEventLog()
        log.append(agent, "agent.moved", 1L, mapper.createObjectNode())
        log.append(agent, "agent.moved", 2L, mapper.createObjectNode())

        AgentContextHolder.set(agent)
        val resource = AgentEventResource(log, mapper)
        val result = resource.read(exchange, ReadResourceRequest("agent://${agent.id}/events"))

        val parsed = mapper.readTree((result.contents().single() as TextResourceContents).text())
        assertEquals(2, parsed.size())
    }

    @Test
    fun `read rejects URIs that don't match the resource template`() {
        AgentContextHolder.set(agent)
        val resource = AgentEventResource(FakeAgentEventLog(), mapper)

        assertThrows<IllegalArgumentException> {
            resource.read(exchange, ReadResourceRequest("agent://not-a-uuid/events"))
        }
        assertThrows<IllegalArgumentException> {
            resource.read(exchange, ReadResourceRequest("https://example.com/events"))
        }
        assertThrows<IllegalArgumentException> {
            resource.read(exchange, ReadResourceRequest("agent://${agent.id}/events?after=abc"))
        }
    }

    @Test
    fun `read refuses to access another agent's log`() {
        val intruder = AgentId(UUID.randomUUID())
        val log = FakeAgentEventLog()
        log.append(agent, "agent.spawned", 1L, mapper.createObjectNode())

        AgentContextHolder.set(intruder)
        val resource = AgentEventResource(log, mapper)

        assertThrows<IllegalArgumentException> {
            resource.read(exchange, ReadResourceRequest("agent://${agent.id}/events"))
        }
        // Original agent's log must remain intact
        assertEquals(1, log.since(agent, 0).size)
    }
}
