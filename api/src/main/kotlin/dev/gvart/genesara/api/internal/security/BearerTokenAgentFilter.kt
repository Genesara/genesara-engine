package dev.gvart.genesara.api.internal.security

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.player.AgentRegistry
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

internal class BearerTokenAgentFilter(
    private val registry: AgentRegistry,
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        val header = request.getHeader("Authorization")
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            chain.doFilter(request, response)
            return
        }
        val token = header.substring(BEARER_PREFIX.length).trim()
        val agent = registry.findByToken(token)
        if (agent == null) {
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
            SecurityContextHolder.clearContext()
        }
    }

    private companion object {
        const val BEARER_PREFIX = "Bearer "
    }
}
