package dev.gvart.genesara.api.internal.rest.events

import dev.gvart.genesara.account.Player
import dev.gvart.genesara.account.PlayerId
import dev.gvart.genesara.api.internal.mcp.events.FakeAgentEventLog
import dev.gvart.genesara.api.internal.rest.OwnedAgentResolver
import dev.gvart.genesara.player.Agent
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentRegistry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.ObjectMapper
import java.util.UUID
import kotlin.test.assertEquals

class AgentEventsControllerTest {

    private val player = Player(id = PlayerId(UUID.randomUUID()), username = "alice", apiToken = "plr_x")
    private val agent = Agent(id = AgentId(UUID.randomUUID()), owner = player.id, name = "Ada")
    private val mapper = ObjectMapper()

    @Test
    fun `list returns events strictly after the seq cursor and respects the limit`() {
        val log = FakeAgentEventLog().also {
            it.append(agent.id, "agent.spawned", tick = 1, payload = mapper.createObjectNode())
            it.append(agent.id, "agent.moved", tick = 2, payload = mapper.createObjectNode())
            it.append(agent.id, "agent.moved", tick = 3, payload = mapper.createObjectNode())
        }
        val controller = controller(log)

        val all = controller.list(player, agent.id.id, after = 0, limit = 50)
        assertEquals(listOf(1L, 2L, 3L), all.map { it.seq })

        val afterFirst = controller.list(player, agent.id.id, after = 1, limit = 50)
        assertEquals(listOf(2L, 3L), afterFirst.map { it.seq })

        val capped = controller.list(player, agent.id.id, after = 0, limit = 1)
        assertEquals(listOf(1L), capped.map { it.seq })
    }

    @Test
    fun `list rejects a request for an agent the player does not own with 404`() {
        val foreign = Agent(id = AgentId(UUID.randomUUID()), owner = PlayerId(UUID.randomUUID()), name = "Theirs")
        val controller = controller(FakeAgentEventLog(), agentsKnown = listOf(foreign))

        val ex = assertThrows<ResponseStatusException> {
            controller.list(player, foreign.id.id, after = 0, limit = 50)
        }
        assertEquals(HttpStatus.NOT_FOUND, ex.statusCode)
    }

    private fun controller(log: FakeAgentEventLog, agentsKnown: List<Agent> = listOf(agent)): AgentEventsController {
        val resolver = OwnedAgentResolver(StubRegistry(agentsKnown))
        return AgentEventsController(resolver, log, NoopBroker)
    }

    private class StubRegistry(private val agents: List<Agent>) : AgentRegistry {
        override fun find(id: AgentId): Agent? = agents.firstOrNull { it.id == id }
        override fun listForOwner(owner: PlayerId): List<Agent> = agents.filter { it.owner == owner }
    }

    private object NoopBroker : AgentEventsBroker {
        override fun register(agentId: AgentId, afterSeq: Long, timeoutMs: Long) =
            error("SSE register() should not be called from poll-path tests")
    }
}
