package dev.gvart.genesara.api.internal.rest

import dev.gvart.genesara.account.Player
import dev.gvart.genesara.account.PlayerId
import dev.gvart.genesara.account.PlayerRegistrar
import dev.gvart.genesara.account.UsernameAlreadyExists
import dev.gvart.genesara.api.internal.security.jwt.JwtIssuer
import dev.gvart.genesara.api.internal.security.jwt.JwtProperties
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PlayerRegistrationControllerTest {

    private val jwtIssuer = JwtIssuer(JwtProperties(secret = "test-secret-please-use-32-bytes-min-aaaa"))

    @Test
    fun `register returns 201 with the new player id, api token, and JWT`() {
        val player = Player(
            id = PlayerId(UUID.randomUUID()),
            username = "alice",
            apiToken = "plr_abc",
        )
        val controller = PlayerRegistrationController(StubRegistrar.returning(player), jwtIssuer)

        val response = controller.register(PlayerRegistrationController.RegisterRequest("alice", "secret12"))

        assertEquals(HttpStatus.CREATED, response.statusCode)
        val body = response.body!!
        assertEquals(player.id.id, body.playerId)
        assertEquals("plr_abc", body.apiToken)
        assertTrue(body.token.isNotBlank(), "JWT should be issued at registration")
        assertEquals(player.id, jwtIssuer.parseSubject(body.token))
    }

    @Test
    fun `register propagates UsernameAlreadyExists for the global advice to convert`() {
        val controller = PlayerRegistrationController(StubRegistrar.failing(), jwtIssuer)

        val thrown = assertFailsWith<UsernameAlreadyExists> {
            controller.register(PlayerRegistrationController.RegisterRequest("alice", "secret12"))
        }
        assertEquals("alice", thrown.username)
    }

    private class StubRegistrar private constructor(
        private val player: Player?,
    ) : PlayerRegistrar {
        override fun register(username: String, password: String): Player =
            player ?: throw UsernameAlreadyExists(username)

        companion object {
            fun returning(player: Player) = StubRegistrar(player)
            fun failing() = StubRegistrar(null)
        }
    }
}
