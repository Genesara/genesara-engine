package dev.gvart.agenticrpg.api.internal.rest.agent

import dev.gvart.agenticrpg.api.internal.mcp.tools.lookaround.LookAroundResponse
import dev.gvart.agenticrpg.api.internal.mcp.tools.lookaround.NodeView
import dev.gvart.agenticrpg.engine.TickClock
import dev.gvart.agenticrpg.player.Agent
import dev.gvart.agenticrpg.player.AgentRegistry
import dev.gvart.agenticrpg.player.ClassPropertiesLookup
import dev.gvart.agenticrpg.world.Node
import dev.gvart.agenticrpg.world.NodeId
import dev.gvart.agenticrpg.world.Region
import dev.gvart.agenticrpg.world.WorldCommandGateway
import dev.gvart.agenticrpg.world.WorldQueryGateway
import dev.gvart.agenticrpg.world.commands.WorldCommand
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
 * REST mirror of the MCP runtime tools. The CLAUDE.md roadmap calls for a REST API that
 * mirrors the MCP tools for non-MCP clients; this controller is that surface for the three
 * Phase 0 tools (`spawn`, `move`, `look_around`).
 *
 * Auth: same bearer-token chain as `/sse` and `/mcp/message` — the [BearerTokenAgentFilter]
 * resolves the agent from the `Authorization: Bearer <apiToken>` header and Spring injects it
 * via [AuthenticationPrincipal].
 */
@RestController
@RequestMapping("/api/agent/me")
internal class AgentRuntimeController(
    private val command: WorldCommandGateway,
    private val query: WorldQueryGateway,
    private val tick: TickClock,
    private val agents: AgentRegistry,
    private val classes: ClassPropertiesLookup,
) {

    data class CommandRequest(val nodeId: Long)
    data class CommandResponse(val commandId: UUID, val appliesAtTick: Long)

    @PostMapping("/spawn")
    fun spawn(
        @AuthenticationPrincipal agent: Agent,
        @RequestBody req: CommandRequest,
    ): ResponseEntity<CommandResponse> {
        val nextTick = tick.currentTick() + 1
        val cmd = WorldCommand.SpawnAgent(agent.id, NodeId(req.nodeId))
        command.submit(cmd, appliesAtTick = nextTick)
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(CommandResponse(cmd.commandId, nextTick))
    }

    @PostMapping("/move")
    fun move(
        @AuthenticationPrincipal agent: Agent,
        @RequestBody req: CommandRequest,
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
        val sight = classes.sightRange(agentRecord.classId)
        val nodeId = query.locationOf(agent.id)
            ?: return ResponseEntity.status(HttpStatus.CONFLICT).build()
        val current = query.node(nodeId)
            ?: return ResponseEntity.notFound().build()
        val region = query.region(current.regionId)
            ?: return ResponseEntity.notFound().build()
        val visible = query.nodesWithin(nodeId, sight)
            .asSequence()
            .filter { it != nodeId }
            .mapNotNull { id ->
                val n = query.node(id) ?: return@mapNotNull null
                val r = query.region(n.regionId) ?: return@mapNotNull null
                n to r
            }
            .toList()
        return ResponseEntity.ok(
            LookAroundResponse(
                currentNode = current.toView(region),
                adjacent = visible.map { (n, r) -> n.toView(r) },
            ),
        )
    }

    private fun Node.toView(region: Region) = NodeView(
        id = id.value,
        q = q,
        r = r,
        biome = region.biome?.name,
        climate = region.climate?.name,
        terrain = terrain.name,
        resources = emptyList(),
    )
}
