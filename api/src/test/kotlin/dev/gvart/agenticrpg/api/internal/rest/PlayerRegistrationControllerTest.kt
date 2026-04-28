package dev.gvart.agenticrpg.api.internal.rest

import dev.gvart.agenticrpg.account.Player
import dev.gvart.agenticrpg.account.PlayerId
import dev.gvart.agenticrpg.account.PlayerRegistrar
import dev.gvart.agenticrpg.account.UsernameAlreadyExists
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import java.util.UUID
import kotlin.test.assertEquals

class PlayerRegistrationControllerTest {

    @Test
    fun `register returns 201 with the new player id`() {
        val player = Player(id = PlayerId(UUID.randomUUID()), username = "alice")
        val controller = PlayerRegistrationController(StubRegistrar.returning(player))

        val response = controller.register(PlayerRegistrationController.RegisterRequest("alice", "secret"))

        assertEquals(HttpStatus.CREATED, response.statusCode)
        assertEquals(player.id.id, response.body!!.playerId)
    }

    @Test
    fun `handler converts UsernameAlreadyExists to 409`() {
        val controller = PlayerRegistrationController(StubRegistrar.failing())

        val response = controller.handleConflict(UsernameAlreadyExists("alice"))

        assertEquals(HttpStatus.CONFLICT, response.statusCode)
        assertEquals("Username 'alice' is already taken", response.body!!.error)
    }

    @Test
    fun `register propagates UsernameAlreadyExists for the @ExceptionHandler to catch`() {
        val controller = PlayerRegistrationController(StubRegistrar.failing())
        var thrown: UsernameAlreadyExists? = null
        try {
            controller.register(PlayerRegistrationController.RegisterRequest("alice", "secret"))
        } catch (e: UsernameAlreadyExists) {
            thrown = e
        }
        assertEquals("alice", thrown!!.username)
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
