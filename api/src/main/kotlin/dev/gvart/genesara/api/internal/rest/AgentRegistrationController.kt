package dev.gvart.genesara.api.internal.rest

import dev.gvart.genesara.account.Player
import dev.gvart.genesara.player.AgentRegistrar
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/agents")
internal class AgentRegistrationController(
    private val registrar: AgentRegistrar,
) {

    data class RegisterRequest(val name: String)
    data class RegisterResponse(val agentId: UUID, val apiToken: String)

    @PostMapping
    fun register(
        @AuthenticationPrincipal player: Player,
        @RequestBody req: RegisterRequest,
    ): ResponseEntity<RegisterResponse> {
        val agent = registrar.register(player.id, req.name)
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(RegisterResponse(agent.id.id, agent.apiToken))
    }
}
