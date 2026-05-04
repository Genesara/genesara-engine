package dev.gvart.genesara.api.internal.security

import dev.gvart.genesara.account.Player
import dev.gvart.genesara.account.PlayerId
import dev.gvart.genesara.account.PlayerLookup
import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.player.Agent
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentRegistry
import jakarta.servlet.FilterChain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerApiTokenAgentFilterTest {

    private val owner = PlayerId(UUID.randomUUID())
    private val player = Player(id = owner, username = "alice", apiToken = "plr_valid")
    private val agent = Agent(id = AgentId(UUID.randomUUID()), owner = owner, name = "alpha")
    private val otherAgent = Agent(id = AgentId(UUID.randomUUID()), owner = PlayerId(UUID.randomUUID()), name = "beta")

    private val players = StubPlayers(player)
    private val agents = StubAgents(setOf(agent, otherAgent))
    private val filter = PlayerApiTokenAgentFilter(players, agents)

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `populates context when token + X-Agent-Id resolve to a player-owned agent`() {
       val req = MockHttpServletRequest().apply {
            addHeader("Authorization", "Bearer plr_valid")
            addHeader("X-Agent-Id", agent.id.id.toString())
        }
        val chain = CapturingChain()

        filter.doFilter(req, MockHttpServletResponse(), chain)

        val captured = assertNotNull(chain.snapshot)
        assertEquals(agent.id, captured.agentInContext)
        assertEquals(agent, captured.principal)
        assertTrue(captured.authorities.any { it.authority == "ROLE_AGENT" })
        assertEquals(agent, SecurityContextHolder.getContext().authentication!!.principal)
        assertAgentContextCleared()
    }

    @Test
    fun `falls through when X-Agent-Id is missing`() {
        val req = MockHttpServletRequest().apply { addHeader("Authorization", "Bearer plr_valid") }
        val chain = CapturingChain()

        filter.doFilter(req, MockHttpServletResponse(), chain)

        assertNull(chain.snapshot)
    }

    @Test
    fun `falls through when X-Agent-Id is not a UUID`() {
        val req = MockHttpServletRequest().apply {
            addHeader("Authorization", "Bearer plr_valid")
            addHeader("X-Agent-Id", "not-a-uuid")
        }
        val chain = CapturingChain()

        filter.doFilter(req, MockHttpServletResponse(), chain)

        assertNull(chain.snapshot)
    }

    @Test
    fun `falls through when player api token is unknown`() {
        val req = MockHttpServletRequest().apply {
            addHeader("Authorization", "Bearer plr_unknown")
            addHeader("X-Agent-Id", agent.id.id.toString())
        }
        val chain = CapturingChain()

        filter.doFilter(req, MockHttpServletResponse(), chain)

        assertNull(chain.snapshot)
    }

    @Test
    fun `falls through when agent belongs to a different player`() {
        val req = MockHttpServletRequest().apply {
            addHeader("Authorization", "Bearer plr_valid")
            addHeader("X-Agent-Id", otherAgent.id.id.toString())
        }
        val chain = CapturingChain()

        filter.doFilter(req, MockHttpServletResponse(), chain)

        assertNull(chain.snapshot)
    }

    @Test
    fun `clears agent context when downstream chain throws`() {
       val req = MockHttpServletRequest().apply {
            addHeader("Authorization", "Bearer plr_valid")
            addHeader("X-Agent-Id", agent.id.id.toString())
        }
        val throwing = ThrowingChain()

        try { filter.doFilter(req, MockHttpServletResponse(), throwing) } catch (_: RuntimeException) {}

        assertAgentContextCleared()
    }

    private fun assertAgentContextCleared() {
        try {
            AgentContextHolder.current()
            error("AgentContextHolder.current() should have thrown after clear")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("No AgentId in context"))
        }
    }

    private class StubPlayers(private val player: Player) : PlayerLookup {
        override fun find(id: PlayerId): Player? = if (id == player.id) player else null
        override fun findByUsername(username: String): Player? = null
        override fun findByApiToken(token: String): Player? = if (token == player.apiToken) player else null
    }

    private class StubAgents(private val agents: Set<Agent>) : AgentRegistry {
        override fun find(id: AgentId): Agent? = agents.firstOrNull { it.id == id }
        override fun listForOwner(owner: PlayerId): List<Agent> = agents.filter { it.owner == owner }
    }

    private class CapturingChain : FilterChain {
        var snapshot: Snapshot? = null
        override fun doFilter(req: jakarta.servlet.ServletRequest, res: jakarta.servlet.ServletResponse) {
            val auth = SecurityContextHolder.getContext().authentication ?: return
            val agentId = try { AgentContextHolder.current() } catch (_: IllegalStateException) { null } ?: return
            snapshot = Snapshot(agentId, auth.principal as Agent, auth.authorities.toList())
        }
        data class Snapshot(
            val agentInContext: AgentId,
            val principal: Agent,
            val authorities: List<org.springframework.security.core.GrantedAuthority>,
        )
    }

    private class ThrowingChain : FilterChain {
        override fun doFilter(req: jakarta.servlet.ServletRequest, res: jakarta.servlet.ServletResponse) {
            throw RuntimeException("downstream blew up")
        }
    }
}
