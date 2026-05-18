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
import dev.gvart.genesara.world.VisibleNodes
import dev.gvart.genesara.world.WorldCommandGateway
import dev.gvart.genesara.world.WorldQueryGateway
import dev.gvart.genesara.world.commands.CoreCommand
import jakarta.validation.Valid
import jakarta.validation.constraints.Positive
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

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
    private val vision: VisibleNodes,
) {

    data class CommandRequest(@field:Positive val nodeId: Long)
    data class CommandResponse(val commandId: UUID, val appliesAtTick: Long)

    @PostMapping("/spawn")
    fun spawn(@AuthenticationPrincipal agent: Agent): ResponseEntity<CommandResponse> {
        val cmd = CoreCommand.SpawnAgent(agent.id)
        val appliesAtTick = command.submit(cmd, appliesAtTick = tick.currentTick() + 1)
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(CommandResponse(cmd.commandId, appliesAtTick))
    }

    @PostMapping("/move")
    fun move(
        @AuthenticationPrincipal agent: Agent,
        @Valid @RequestBody req: CommandRequest,
    ): ResponseEntity<CommandResponse> {
        val cmd = CoreCommand.MoveAgent(agent.id, NodeId(req.nodeId))
        val appliesAtTick = command.submit(cmd, appliesAtTick = tick.currentTick() + 1)
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(CommandResponse(cmd.commandId, appliesAtTick))
    }

    @GetMapping("/look-around")
    fun lookAround(@AuthenticationPrincipal agent: Agent): ResponseEntity<LookAroundResponse> {
        val agentRecord = agents.find(agent.id)
            ?: return ResponseEntity.notFound().build()
        val nodeId = query.locationOf(agent.id)
            ?: return ResponseEntity.status(HttpStatus.CONFLICT).build()
        val current = query.node(nodeId)
            ?: return ResponseEntity.notFound().build()
        val region = query.region(current.regionId)
            ?: return ResponseEntity.notFound().build()
        val currentTick = tick.currentTick()
        val currentResources = query.resourcesAt(current.id, currentTick)
        val visible = vision.visibleNodesFor(agentRecord, nodeId)
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
                visible = visible.map { (n, r, res) -> n.toView(r, res) },
                neighbours = current.adjacency.map { it.value }.sorted(),
            ),
        )
    }

    private fun Node.toView(region: Region, resources: NodeResources) = NodeView(
        id = id.value,
        q = q,
        r = r,
        biome = region.biome,
        climate = region.climate,
        terrain = terrain,
        pvpEnabled = pvpEnabled,
        resources = resources.entries.keys.map { it.value }.sorted(),
    )
}
