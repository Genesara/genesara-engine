package dev.gvart.genesara.api.internal.rest

import dev.gvart.genesara.account.Player
import dev.gvart.genesara.api.internal.mcp.tools.getmap.GetMapResponse
import dev.gvart.genesara.api.internal.mcp.tools.getmap.RecalledNodeView
import dev.gvart.genesara.world.AgentMapMemoryGateway
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** Agent-memory only — never the full world. Information asymmetry is a feature. */
@RestController
@RequestMapping("/api/agents/{agentId}")
internal class AgentMapController(
    private val owned: OwnedAgentResolver,
    private val mapMemory: AgentMapMemoryGateway,
) {

    @GetMapping("/map")
    fun map(
        @AuthenticationPrincipal player: Player,
        @PathVariable agentId: UUID,
    ): GetMapResponse {
        val agent = owned.resolve(player, agentId)
        return GetMapResponse(
            nodes = mapMemory.recall(agent.id).map {
                RecalledNodeView(
                    nodeId = it.nodeId.value,
                    regionId = it.regionId.value,
                    q = it.q,
                    r = it.r,
                    terrain = it.terrain,
                    biome = it.biome,
                    firstSeenTick = it.firstSeenTick,
                    lastSeenTick = it.lastSeenTick,
                )
            },
        )
    }
}
