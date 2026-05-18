package dev.gvart.genesara.api.internal.rest

import dev.gvart.genesara.account.Player
import dev.gvart.genesara.api.internal.mcp.tools.getstatus.SkillsView
import dev.gvart.genesara.api.internal.projection.AgentSkillsProjection
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/agents/{agentId}")
internal class AgentSkillsController(
    private val owned: OwnedAgentResolver,
    private val skillsProjection: AgentSkillsProjection,
) {

    @GetMapping("/skills")
    fun skills(
        @AuthenticationPrincipal player: Player,
        @PathVariable agentId: UUID,
    ): SkillsView {
        val agent = owned.resolve(player, agentId)
        return skillsProjection.project(agent.id)
    }
}
