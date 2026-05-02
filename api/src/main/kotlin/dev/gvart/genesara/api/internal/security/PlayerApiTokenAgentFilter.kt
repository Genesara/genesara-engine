package dev.gvart.genesara.api.internal.security

import dev.gvart.genesara.account.PlayerLookup
import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentRegistry
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

internal class PlayerApiTokenAgentFilter(
    private val players: PlayerLookup,
    private val agents: AgentRegistry,
) : OncePerRequestFilter() {

    /**
     * Re-run on Servlet async dispatches (Streamable-HTTP / SSE for the MCP
     * endpoint). Without this the auth context is gone by the time the
     * dispatched response is rendered and Spring Security denies it.
     */
    override fun shouldNotFilterAsyncDispatch(): Boolean = false

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        val authHeader = request.getHeader("Authorization")
        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            chain.doFilter(request, response)
            return
        }
        val token = authHeader.substring(BEARER_PREFIX.length).trim()
        val agentIdHeader = request.getHeader(AGENT_ID_HEADER)?.trim()
        if (agentIdHeader.isNullOrEmpty()) {
            chain.doFilter(request, response)
            return
        }
        val agentId = try {
            AgentId(UUID.fromString(agentIdHeader))
        } catch (_: IllegalArgumentException) {
            chain.doFilter(request, response)
            return
        }
        val player = players.findByApiToken(token)
        if (player == null) {
            chain.doFilter(request, response)
            return
        }
        val agent = agents.find(agentId)
        if (agent == null || agent.owner != player.id) {
            chain.doFilter(request, response)
            return
        }
        AgentContextHolder.set(agent.id)
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(
            agent,
            null,
            listOf(SimpleGrantedAuthority("ROLE_AGENT")),
        )
        try {
            chain.doFilter(request, response)
        } finally {
            AgentContextHolder.clear()
        }
    }

    private companion object {
        const val BEARER_PREFIX = "Bearer "
        const val AGENT_ID_HEADER = "X-Agent-Id"
    }
}
