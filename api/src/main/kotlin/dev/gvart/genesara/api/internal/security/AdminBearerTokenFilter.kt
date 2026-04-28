package dev.gvart.genesara.api.internal.security

import dev.gvart.genesara.admin.AdminTokenStore
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

internal class AdminBearerTokenFilter(
    private val tokens: AdminTokenStore,
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
        val admin = tokens.findByToken(token)
        if (admin == null) {
            chain.doFilter(request, response)
            return
        }
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(
            admin,
            null,
            listOf(SimpleGrantedAuthority("ROLE_ADMIN")),
        )
        try {
            chain.doFilter(request, response)
        } finally {
            SecurityContextHolder.clearContext()
        }
    }

    private companion object {
        const val BEARER_PREFIX = "Bearer "
    }
}
