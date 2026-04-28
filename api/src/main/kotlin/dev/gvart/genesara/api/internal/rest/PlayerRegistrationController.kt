package dev.gvart.genesara.api.internal.rest

import dev.gvart.genesara.account.PlayerRegistrar
import dev.gvart.genesara.account.UsernameAlreadyExists
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
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

    data class RegisterRequest(val username: String, val password: String)
    data class RegisterResponse(val playerId: UUID)
    data class ErrorResponse(val error: String)

    @PostMapping
    fun register(@RequestBody req: RegisterRequest): ResponseEntity<RegisterResponse> {
        val player = registrar.register(req.username, req.password)
        return ResponseEntity.status(HttpStatus.CREATED).body(RegisterResponse(player.id.id))
    }

    @ExceptionHandler(UsernameAlreadyExists::class)
    fun handleConflict(e: UsernameAlreadyExists): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse(e.message ?: "username taken"))
}
