package dev.gvart.genesara.api.internal.rest

import dev.gvart.genesara.account.AccountAuthenticator
import dev.gvart.genesara.account.Player
import dev.gvart.genesara.account.PlayerId
import dev.gvart.genesara.api.internal.security.jwt.JwtIssuer
import dev.gvart.genesara.api.internal.security.jwt.JwtProperties
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlayerLoginControllerTest {

    private val player = Player(
        id = PlayerId(UUID.randomUUID()),
        username = "alice",
        apiToken = "plr_token",
    )
    private val issuer = JwtIssuer(
        JwtProperties(secret = "test-secret-must-be-at-least-32-bytes-long-1234", ttl = Duration.ofHours(1)),
        Clock.fixed(Instant.parse("2026-05-02T00:00:00Z"), ZoneOffset.UTC),
    )

    @Test
    fun `valid credentials return a JWT whose subject is the player id`() {
        val controller = PlayerLoginController(StubAuthenticator(player), issuer)

        val response = controller.login(PlayerLoginController.LoginRequest("alice", "secret12"))

        assertTrue(response.token.isNotBlank())
        assertEquals(player.id, issuer.parseSubject(response.token))
    }

    @Test
    fun `invalid credentials raise 401`() {
        val controller = PlayerLoginController(StubAuthenticator(null), issuer)

        val ex = assertThrows<ResponseStatusException> {
            controller.login(PlayerLoginController.LoginRequest("alice", "wrong"))
        }
        assertEquals(HttpStatus.UNAUTHORIZED, ex.statusCode)
    }

    private class StubAuthenticator(private val outcome: Player?) : AccountAuthenticator {
        override fun verify(username: String, password: String): Player? = outcome
    }
}
