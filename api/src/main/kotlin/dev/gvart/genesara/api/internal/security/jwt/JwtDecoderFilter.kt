package dev.gvart.genesara.api.internal.security.jwt

import dev.gvart.genesara.account.PlayerLookup
import io.jsonwebtoken.JwtException
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

internal class JwtDecoderFilter(
    private val issuer: JwtIssuer,
    private val players: PlayerLookup,
) : OncePerRequestFilter() {

    /**
     * Re-run on async dispatches so the auth context is re-established before
     * the response is rendered. Defensive — today these endpoints are pure
     * REST, but matching the MCP filter avoids future surprises.
     */
    override fun shouldNotFilterAsyncDispatch(): Boolean = false

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
        val playerId = try {
            issuer.parseSubject(token)
        } catch (_: JwtException) {
            chain.doFilter(request, response)
            return
        } catch (_: IllegalArgumentException) {
            chain.doFilter(request, response)
            return
        }
        val player = players.find(playerId)
        if (player == null) {
            chain.doFilter(request, response)
            return
        }
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(
            player,
            null,
            listOf(SimpleGrantedAuthority("ROLE_PLAYER")),
        )
        chain.doFilter(request, response)
    }

    private companion object {
        const val BEARER_PREFIX = "Bearer "
    }
}
