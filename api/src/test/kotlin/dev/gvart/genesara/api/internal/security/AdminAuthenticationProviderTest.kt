package dev.gvart.genesara.api.internal.security

import dev.gvart.genesara.admin.Admin
import dev.gvart.genesara.admin.AdminAuthenticator
import dev.gvart.genesara.admin.AdminId
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdminAuthenticationProviderTest {

    private val admin = Admin(id = AdminId(UUID.randomUUID()), username = "ops")

    @Test
    fun `returns ROLE_ADMIN token when credentials are valid`() {
        val provider = AdminAuthenticationProvider(StubAuthenticator(expecting = "ops" to "topsecret", returning = admin))

        val result = provider.authenticate(UsernamePasswordAuthenticationToken("ops", "topsecret"))

        assertEquals(admin, result.principal)
        assertTrue(result.isAuthenticated)
        assertTrue(result.authorities.any { it.authority == "ROLE_ADMIN" })
    }

    @Test
    fun `throws BadCredentialsException when password is missing`() {
        val provider = AdminAuthenticationProvider(StubAuthenticator(returning = null))

        assertThrows<BadCredentialsException> {
            provider.authenticate(UsernamePasswordAuthenticationToken("ops", null))
        }
    }

    @Test
    fun `throws BadCredentialsException when the authenticator rejects the credentials`() {
        val provider = AdminAuthenticationProvider(StubAuthenticator(returning = null))

        assertThrows<BadCredentialsException> {
            provider.authenticate(UsernamePasswordAuthenticationToken("ops", "wrong"))
        }
    }

    @Test
    fun `supports UsernamePasswordAuthenticationToken and rejects others`() {
        val provider = AdminAuthenticationProvider(StubAuthenticator(returning = null))

        assertTrue(provider.supports(UsernamePasswordAuthenticationToken::class.java))
        assertTrue(!provider.supports(UnsupportedToken::class.java))
    }

    private class StubAuthenticator(
        private val expecting: Pair<String, String>? = null,
        private val returning: Admin?,
    ) : AdminAuthenticator {
        override fun verify(username: String, password: String): Admin? {
            if (expecting != null && expecting != username to password) return null
            return returning
        }
    }

    private class UnsupportedToken
}
