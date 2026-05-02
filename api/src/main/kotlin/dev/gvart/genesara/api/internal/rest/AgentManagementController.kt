package dev.gvart.genesara.api.internal.rest

import dev.gvart.genesara.account.Player
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityTracker
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.world.WorldAgentPurger
import dev.gvart.genesara.world.WorldQueryGateway
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@RestController
@RequestMapping("/api/agents")
internal class AgentManagementController(
    private val agents: AgentRegistry,
    private val world: WorldQueryGateway,
    private val activity: AgentActivityTracker,
    private val purger: WorldAgentPurger,
) {

    @GetMapping
    fun list(@AuthenticationPrincipal player: Player): List<AgentSummary> {
        val owned = agents.listForOwner(player.id)
        if (owned.isEmpty()) return emptyList()
        val lastActive = activity.lastActiveBatch(owned.map { it.id })
        return owned.map { agent ->
            projectAgentSummary(
                agent = agent,
                body = world.bodyOf(agent.id),
                location = world.locationOf(agent.id),
                activeNode = world.activePositionOf(agent.id),
                lastActiveAt = lastActive[agent.id],
            )
        }
    }

    @DeleteMapping("/{agentId}")
    fun delete(
        @AuthenticationPrincipal player: Player,
        @PathVariable agentId: UUID,
    ): ResponseEntity<Void> {
        val target = agents.find(AgentId(agentId))
        if (target == null || target.owner != player.id) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Agent not found")
        }
        // Order matters: drop the agents row first so any concurrent tick reducer that drains a
        // queued command for this agent sees no character state and short-circuits. Purger second
        // cleans world-side rows (no FK back to agents); activity.forget last clears the Redis hot
        // entry. A failure between steps leaves orphan world rows or a stale Redis entry — both
        // tolerable; the sweep eventually wins.
        agents.delete(target.id)
        purger.purge(target.id)
        activity.forget(target.id)
        return ResponseEntity.noContent().build()
    }
}
