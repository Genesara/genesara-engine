package dev.gvart.agenticrpg.api.internal.security

import dev.gvart.agenticrpg.account.AccountAuthenticator
import dev.gvart.agenticrpg.account.Player
import dev.gvart.agenticrpg.account.PlayerId
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AccountAuthenticationProviderTest {

    private val player = Player(id = PlayerId(UUID.randomUUID()), username = "alice")

    @Test
    fun `returns ROLE_PLAYER token when credentials are valid`() {
        val provider = AccountAuthenticationProvider(StubAuthenticator(expecting = "alice" to "secret", returning = player))

        val result = provider.authenticate(UsernamePasswordAuthenticationToken("alice", "secret"))

        assertEquals(player, result.principal)
        assertTrue(result.isAuthenticated)
        assertTrue(result.authorities.any { it.authority == "ROLE_PLAYER" })
    }

    @Test
    fun `throws BadCredentialsException when password is missing`() {
        val provider = AccountAuthenticationProvider(StubAuthenticator(returning = null))

        assertThrows<BadCredentialsException> {
            provider.authenticate(UsernamePasswordAuthenticationToken("alice", null))
        }
    }

    @Test
    fun `throws BadCredentialsException when the authenticator rejects the credentials`() {
        val provider = AccountAuthenticationProvider(StubAuthenticator(returning = null))

        assertThrows<BadCredentialsException> {
            provider.authenticate(UsernamePasswordAuthenticationToken("alice", "wrong"))
        }
    }

    @Test
    fun `supports UsernamePasswordAuthenticationToken and rejects others`() {
        val provider = AccountAuthenticationProvider(StubAuthenticator(returning = null))

        assertTrue(provider.supports(UsernamePasswordAuthenticationToken::class.java))
        assertTrue(!provider.supports(UnsupportedToken::class.java))
    }

    private class StubAuthenticator(
        private val expecting: Pair<String, String>? = null,
        private val returning: Player?,
    ) : AccountAuthenticator {
        override fun verify(username: String, password: String): Player? {
            if (expecting != null && expecting != username to password) return null
            return returning
        }
    }

    private class UnsupportedToken
}
