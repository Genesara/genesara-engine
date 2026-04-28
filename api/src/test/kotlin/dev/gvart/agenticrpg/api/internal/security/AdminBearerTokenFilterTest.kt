package dev.gvart.agenticrpg.api.internal.security

import dev.gvart.agenticrpg.admin.Admin
import dev.gvart.agenticrpg.admin.AdminId
import dev.gvart.agenticrpg.admin.AdminTokenStore
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

class AdminBearerTokenFilterTest {

    private val admin = Admin(id = AdminId(UUID.randomUUID()), username = "ops")
    private val tokens = StubTokenStore(token = "valid-admin", admin = admin)
    private val filter = AdminBearerTokenFilter(tokens)

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `populates ROLE_ADMIN on a valid token and clears the context after the chain runs`() {
        val request = MockHttpServletRequest().apply { addHeader("Authorization", "Bearer valid-admin") }
        val response = MockHttpServletResponse()
        val chain = CapturingChain()

        filter.doFilter(request, response, chain)

        assertEquals(1, chain.invocations)
        val captured = assertNotNull(chain.snapshot)
        assertEquals(admin, captured.principal)
        assertTrue(captured.authorities.any { it.authority == "ROLE_ADMIN" })
        assertNull(SecurityContextHolder.getContext().authentication)
    }

    @Test
    fun `passes through with no auth when the Authorization header is missing`() {
        val chain = CapturingChain()

        filter.doFilter(MockHttpServletRequest(), MockHttpServletResponse(), chain)

        assertNull(chain.snapshot)
        assertNull(SecurityContextHolder.getContext().authentication)
    }

    @Test
    fun `passes through with no auth when the token is unknown`() {
        val request = MockHttpServletRequest().apply { addHeader("Authorization", "Bearer not-a-token") }
        val chain = CapturingChain()

        filter.doFilter(request, MockHttpServletResponse(), chain)

        assertNull(chain.snapshot)
        assertNull(SecurityContextHolder.getContext().authentication)
    }

    @Test
    fun `clears context even when downstream throws`() {
        val request = MockHttpServletRequest().apply { addHeader("Authorization", "Bearer valid-admin") }
        val chain = ThrowingChain()

        try {
            filter.doFilter(request, MockHttpServletResponse(), chain)
        } catch (_: RuntimeException) {
            // expected
        }

        assertNull(SecurityContextHolder.getContext().authentication)
    }

    private class StubTokenStore(private val token: String, private val admin: Admin) : AdminTokenStore {
        override fun issue(adminId: AdminId): String = token
        override fun findByToken(token: String): Admin? = if (token == this.token) admin else null
        override fun revoke(token: String) = Unit
    }

    private class CapturingChain : FilterChain {
        var invocations = 0
        var snapshot: Snapshot? = null

        override fun doFilter(request: jakarta.servlet.ServletRequest, response: jakarta.servlet.ServletResponse) {
            invocations++
            val auth = SecurityContextHolder.getContext().authentication ?: return
            snapshot = Snapshot(
                principal = auth.principal as Admin,
                authorities = auth.authorities.toList(),
            )
        }

        data class Snapshot(
            val principal: Admin,
            val authorities: List<org.springframework.security.core.GrantedAuthority>,
        )
    }

    private class ThrowingChain : FilterChain {
        override fun doFilter(request: jakarta.servlet.ServletRequest, response: jakarta.servlet.ServletResponse) {
            throw RuntimeException("boom")
        }
    }
}
