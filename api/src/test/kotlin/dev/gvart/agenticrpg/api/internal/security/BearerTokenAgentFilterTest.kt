package dev.gvart.agenticrpg.api.internal.security

import dev.gvart.agenticrpg.account.PlayerId
import dev.gvart.agenticrpg.api.internal.mcp.context.AgentContextHolder
import dev.gvart.agenticrpg.player.Agent
import dev.gvart.agenticrpg.player.AgentId
import dev.gvart.agenticrpg.player.AgentRegistry
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
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

class BearerTokenAgentFilterTest {

    private val agent = Agent(
        id = AgentId(UUID.randomUUID()),
        owner = PlayerId(UUID.randomUUID()),
        name = "alpha",
        apiToken = "valid-token",
    )
    private val registry = StubRegistry(agent)
    private val filter = BearerTokenAgentFilter(registry)

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `populates context for a valid Bearer token and clears it after the chain runs`() {
        val request = MockHttpServletRequest().apply { addHeader("Authorization", "Bearer valid-token") }
        val response = MockHttpServletResponse()
        val chain = CapturingChain()

        filter.doFilter(request, response, chain)

        assertEquals(1, chain.invocations)
        // Captured during chain execution.
        val captured = assertNotNull(chain.snapshot)
        assertEquals(agent.id, captured.agentInContext)
        assertEquals(agent, captured.principal)
        assertTrue(captured.authorities.any { it.authority == "ROLE_AGENT" })
        // Cleared after.
        assertNull(SecurityContextHolder.getContext().authentication)
        assertThrowsClearedAgentContext()
    }

    @Test
    fun `passes through with no auth when the Authorization header is missing`() {
        val request = MockHttpServletRequest()
        val response = MockHttpServletResponse()
        val chain = CapturingChain()

        filter.doFilter(request, response, chain)

        assertEquals(1, chain.invocations)
        assertNull(chain.snapshot)
        assertNull(SecurityContextHolder.getContext().authentication)
    }

    @Test
    fun `passes through with no auth when the header is not a Bearer token`() {
        val request = MockHttpServletRequest().apply { addHeader("Authorization", "Basic foo:bar") }
        val response = MockHttpServletResponse()
        val chain = CapturingChain()

        filter.doFilter(request, response, chain)

        assertEquals(1, chain.invocations)
        assertNull(chain.snapshot)
        assertNull(SecurityContextHolder.getContext().authentication)
    }

    @Test
    fun `passes through with no auth when the token is unknown`() {
        val request = MockHttpServletRequest().apply { addHeader("Authorization", "Bearer wrong-token") }
        val response = MockHttpServletResponse()
        val chain = CapturingChain()

        filter.doFilter(request, response, chain)

        assertEquals(1, chain.invocations)
        assertNull(chain.snapshot)
        assertNull(SecurityContextHolder.getContext().authentication)
    }

    @Test
    fun `clears context even when the downstream chain throws`() {
        val request = MockHttpServletRequest().apply { addHeader("Authorization", "Bearer valid-token") }
        val response = MockHttpServletResponse()
        val chain = ThrowingChain()

        try {
            filter.doFilter(request, response, chain)
        } catch (_: RuntimeException) {
            // expected
        }

        assertNull(SecurityContextHolder.getContext().authentication)
        assertThrowsClearedAgentContext()
    }

    private fun assertThrowsClearedAgentContext() {
        try {
            AgentContextHolder.current()
            error("AgentContextHolder.current() should have thrown after clear")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("No AgentId in context"))
        }
    }

    private class StubRegistry(private val agent: Agent) : AgentRegistry {
        override fun find(id: AgentId): Agent? = if (id == agent.id) agent else null
        override fun findByToken(token: String): Agent? = if (token == agent.apiToken) agent else null
        override fun listForOwner(owner: PlayerId): List<Agent> = emptyList()
    }

    private class CapturingChain : FilterChain {
        var invocations = 0
        var snapshot: Snapshot? = null

        override fun doFilter(request: jakarta.servlet.ServletRequest, response: jakarta.servlet.ServletResponse) {
            invocations++
            val auth = SecurityContextHolder.getContext().authentication
            val agentId = try { AgentContextHolder.current() } catch (_: IllegalStateException) { null }
            if (auth != null && agentId != null) {
                snapshot = Snapshot(
                    agentInContext = agentId,
                    principal = auth.principal as Agent,
                    authorities = auth.authorities.toList(),
                )
            }
        }

        data class Snapshot(
            val agentInContext: AgentId,
            val principal: Agent,
            val authorities: List<org.springframework.security.core.GrantedAuthority>,
        )
    }

    private class ThrowingChain : FilterChain {
        override fun doFilter(request: jakarta.servlet.ServletRequest, response: jakarta.servlet.ServletResponse) {
            throw RuntimeException("downstream blew up")
        }
    }
}
