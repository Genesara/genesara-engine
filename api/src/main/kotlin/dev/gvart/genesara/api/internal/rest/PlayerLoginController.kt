package dev.gvart.genesara.api.internal.rest

import dev.gvart.genesara.account.AccountAuthenticator
import dev.gvart.genesara.api.internal.security.jwt.JwtIssuer
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.springframework.http.HttpStatus

@RestController
@RequestMapping("/api/players/login")
internal class PlayerLoginController(
    private val authenticator: AccountAuthenticator,
    private val jwtIssuer: JwtIssuer,
) {

    data class LoginRequest(
        @field:NotBlank val username: String,
        @field:NotBlank val password: String,
    )

    data class LoginResponse(val token: String)

    @PostMapping
    fun login(@Valid @RequestBody req: LoginRequest): LoginResponse {
        val player = authenticator.verify(req.username, req.password)
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials")
        return LoginResponse(token = jwtIssuer.issue(player.id))
    }
}
