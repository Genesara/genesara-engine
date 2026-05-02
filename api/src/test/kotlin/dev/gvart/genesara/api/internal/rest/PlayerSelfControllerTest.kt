package dev.gvart.genesara.api.internal.rest

import dev.gvart.genesara.account.Player
import dev.gvart.genesara.account.PlayerApiTokenStore
import dev.gvart.genesara.account.PlayerId
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals

class PlayerSelfControllerTest {

    private val player = Player(
        id = PlayerId(UUID.randomUUID()),
        username = "alice",
        apiToken = "plr_old",
    )

    @Test
    fun `getApiToken returns the principal's current token`() {
        val controller = PlayerSelfController(NoopTokenStore)

        val response = controller.getApiToken(player)

        assertEquals("plr_old", response.apiToken)
    }

    @Test
    fun `rotate delegates to the store and returns the freshly minted token`() {
        val controller = PlayerSelfController(StubTokenStore("plr_new"))

        val response = controller.rotate(player)

        assertEquals("plr_new", response.apiToken)
    }

    private object NoopTokenStore : PlayerApiTokenStore {
        override fun rotate(playerId: PlayerId): String = error("not used")
    }

    private class StubTokenStore(private val next: String) : PlayerApiTokenStore {
        override fun rotate(playerId: PlayerId): String = next
    }
}
