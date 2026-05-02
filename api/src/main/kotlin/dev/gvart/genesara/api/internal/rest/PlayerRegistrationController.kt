package dev.gvart.genesara.api.internal.rest

import dev.gvart.genesara.account.PlayerRegistrar
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/players")
internal class PlayerRegistrationController(
    private val registrar: PlayerRegistrar,
) {

    data class RegisterRequest(
        @field:NotBlank @field:Size(min = 3, max = 64) val username: String,
        @field:NotBlank @field:Size(min = 8, max = 256) val password: String,
    )

    data class RegisterResponse(val playerId: UUID, val apiToken: String)

    @PostMapping
    fun register(@Valid @RequestBody req: RegisterRequest): ResponseEntity<RegisterResponse> {
        val player = registrar.register(req.username, req.password)
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(RegisterResponse(player.id.id, player.apiToken))
    }
}
