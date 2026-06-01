package dev.gvart.genesara.api.internal.security

import jakarta.servlet.http.HttpServletResponse
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.InsufficientAuthenticationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BearerAuthenticationEntryPointTest {

    private val entryPoint = BearerAuthenticationEntryPoint()

    @Test
    fun `commence responds 401 unauthorized, not 403 forbidden`() {
        val response = MockHttpServletResponse()

        entryPoint.commence(
            MockHttpServletRequest("POST", "/mcp"),
            response,
            InsufficientAuthenticationException("missing bearer"),
        )

        assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.status)
    }

    @Test
    fun `commence emits a bare Bearer challenge`() {
        val response = MockHttpServletResponse()

        entryPoint.commence(
            MockHttpServletRequest("POST", "/mcp"),
            response,
            InsufficientAuthenticationException("missing bearer"),
        )

        assertEquals("Bearer error=\"invalid_token\"", response.getHeader("WWW-Authenticate"))
    }

    @Test
    fun `challenge advertises no OAuth resource metadata so clients do not start an OAuth flow`() {
        val response = MockHttpServletResponse()

        entryPoint.commence(
            MockHttpServletRequest("GET", "/mcp"),
            response,
            InsufficientAuthenticationException("missing bearer"),
        )

        val challenge = response.getHeader("WWW-Authenticate").orEmpty()
        assertTrue(challenge.startsWith("Bearer"))
        assertFalse(challenge.contains("resource_metadata"))
    }
}
