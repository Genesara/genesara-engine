package dev.gvart.genesara.api.internal.security

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.AuthenticationEntryPoint

/**
 * WHY: a bearer chain with no auth mechanism (no httpBasic/formLogin) defaults to Spring's
 * `Http403ForbiddenEntryPoint`, returning 403 for a missing/invalid token instead of 401. The
 * challenge is a bare RFC 6750 `Bearer` with NO `resource_metadata`, so MCP clients don't treat
 * the endpoint as an OAuth-protected resource and start an OAuth flow the server doesn't implement.
 */
internal class BearerAuthenticationEntryPoint : AuthenticationEntryPoint {
    override fun commence(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authException: AuthenticationException,
    ) {
        response.setHeader("WWW-Authenticate", "Bearer error=\"invalid_token\"")
        response.sendError(HttpServletResponse.SC_UNAUTHORIZED)
    }
}
