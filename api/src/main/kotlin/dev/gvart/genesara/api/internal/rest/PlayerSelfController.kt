package dev.gvart.genesara.api.internal.rest

import dev.gvart.genesara.account.Player
import dev.gvart.genesara.account.PlayerApiTokenStore
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/me")
internal class PlayerSelfController(
    private val tokens: PlayerApiTokenStore,
) {

    data class ApiTokenResponse(val apiToken: String)

    @GetMapping("/api-token")
    fun getApiToken(@AuthenticationPrincipal player: Player): ApiTokenResponse =
        ApiTokenResponse(player.apiToken)

    @PostMapping("/api-token/rotate")
    fun rotate(@AuthenticationPrincipal player: Player): ApiTokenResponse =
        ApiTokenResponse(tokens.rotate(player.id))
}
