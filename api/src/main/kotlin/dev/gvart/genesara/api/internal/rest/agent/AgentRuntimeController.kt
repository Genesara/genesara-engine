package dev.gvart.genesara.api.internal.rest.agent

import dev.gvart.genesara.api.internal.mcp.tools.lookaround.LookAroundResponse
import dev.gvart.genesara.api.internal.mcp.tools.lookaround.NodeView
import dev.gvart.genesara.api.internal.mcp.tools.lookaround.ResourceView
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.player.Agent
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.world.Node
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.NodeResources
import dev.gvart.genesara.world.Region
import dev.gvart.genesara.world.VisionRadius
import dev.gvart.genesara.world.WorldCommandGateway
import dev.gvart.genesara.world.WorldQueryGateway
import dev.gvart.genesara.world.commands.WorldCommand
import jakarta.validation.Valid
import jakarta.validation.constraints.Positive
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * REST mirror of the MCP runtime tools for non-MCP clients (Phase 0: `spawn`, `move`, `look_around`).
 * Auth shares the MCP chain — see [PlayerApiTokenAgentFilter][dev.gvart.genesara.api.internal.security.PlayerApiTokenAgentFilter].
 */
@RestController
@RequestMapping("/api/agent/me")
internal class AgentRuntimeController(
    private val command: WorldCommandGateway,
    private val query: WorldQueryGateway,
    private val tick: TickClock,
    private val agents: AgentRegistry,
    private val vision: VisionRadius,
) {

    data class CommandRequest(@field:Positive val nodeId: Long)
    data class CommandResponse(val commandId: UUID, val appliesAtTick: Long)

    @PostMapping("/spawn")
    fun spawn(@AuthenticationPrincipal agent: Agent): ResponseEntity<CommandResponse> {
        val nextTick = tick.currentTick() + 1
        val cmd = WorldCommand.SpawnAgent(agent.id)
        command.submit(cmd, appliesAtTick = nextTick)
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(CommandResponse(cmd.commandId, nextTick))
    }

    @PostMapping("/move")
    fun move(
        @AuthenticationPrincipal agent: Agent,
        @Valid @RequestBody req: CommandRequest,
    ): ResponseEntity<CommandResponse> {
        val nextTick = tick.currentTick() + 1
        val cmd = WorldCommand.MoveAgent(agent.id, NodeId(req.nodeId))
        command.submit(cmd, appliesAtTick = nextTick)
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(CommandResponse(cmd.commandId, nextTick))
    }

    @GetMapping("/look-around")
    fun lookAround(@AuthenticationPrincipal agent: Agent): ResponseEntity<LookAroundResponse> {
        val agentRecord = agents.find(agent.id)
            ?: return ResponseEntity.notFound().build()
        val nodeId = query.locationOf(agent.id)
            ?: return ResponseEntity.status(HttpStatus.CONFLICT).build()
        val current = query.node(nodeId)
            ?: return ResponseEntity.notFound().build()
        val sight = vision.radiusFor(agentRecord, nodeId)
        val region = query.region(current.regionId)
            ?: return ResponseEntity.notFound().build()
        val currentTick = tick.currentTick()
        val currentResources = query.resourcesAt(current.id, currentTick)
        val visible = query.nodesWithin(nodeId, sight)
            .asSequence()
            .filter { it != nodeId }
            .mapNotNull { id ->
                val n = query.node(id) ?: return@mapNotNull null
                val r = query.region(n.regionId) ?: return@mapNotNull null
                val res = query.resourcesAt(n.id, currentTick)
                Triple(n, r, res)
            }
            .toList()
        return ResponseEntity.ok(
            LookAroundResponse(
                currentNode = current.toView(region, currentResources),
                currentResources = currentResources.entries.values.map {
                    ResourceView(
                        itemId = it.itemId.value,
                        quantity = it.quantity,
                        initialQuantity = it.initialQuantity,
                    )
                },
                adjacent = visible.map { (n, r, res) -> n.toView(r, res) },
            ),
        )
    }

    private fun Node.toView(region: Region, resources: NodeResources) = NodeView(
        id = id.value,
        q = q,
        r = r,
        biome = region.biome?.name,
        climate = region.climate?.name,
        terrain = terrain.name,
        pvpEnabled = pvpEnabled,
        resources = resources.entries.keys.map { it.value }.sorted(),
    )
}
