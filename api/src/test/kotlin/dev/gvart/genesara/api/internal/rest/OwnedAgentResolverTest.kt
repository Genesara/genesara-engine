package dev.gvart.genesara.api.internal.rest

import dev.gvart.genesara.account.Player
import dev.gvart.genesara.account.PlayerId
import dev.gvart.genesara.player.Agent
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentRegistry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.util.UUID
import kotlin.test.assertEquals

class OwnedAgentResolverTest {

    private val player = Player(id = PlayerId(UUID.randomUUID()), username = "alice", apiToken = "plr_x")
    private val agent = Agent(id = AgentId(UUID.randomUUID()), owner = player.id, name = "Ada")

    @Test
    fun `resolve returns the agent when the calling player owns it`() {
        val resolver = OwnedAgentResolver(StubRegistry(agent))

        val found = resolver.resolve(player, agent.id.id)

        assertEquals(agent.id, found.id)
    }

    @Test
    fun `resolve returns 404 when no agent matches the id`() {
        val resolver = OwnedAgentResolver(StubRegistry(null))

        val ex = assertThrows<ResponseStatusException> { resolver.resolve(player, UUID.randomUUID()) }

        assertEquals(HttpStatus.NOT_FOUND, ex.statusCode)
    }

    @Test
    fun `resolve returns 404 when the agent belongs to another player so existence is not leaked`() {
        val foreign = Agent(id = AgentId(UUID.randomUUID()), owner = PlayerId(UUID.randomUUID()), name = "Theirs")
        val resolver = OwnedAgentResolver(StubRegistry(foreign))

        val ex = assertThrows<ResponseStatusException> { resolver.resolve(player, foreign.id.id) }

        assertEquals(HttpStatus.NOT_FOUND, ex.statusCode)
    }

    private class StubRegistry(private val match: Agent?) : AgentRegistry {
        override fun find(id: AgentId): Agent? = match?.takeIf { it.id == id }
        override fun listForOwner(owner: PlayerId): List<Agent> = emptyList()
    }
}
