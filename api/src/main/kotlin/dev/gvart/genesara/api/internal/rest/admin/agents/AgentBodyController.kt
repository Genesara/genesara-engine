package dev.gvart.genesara.api.internal.rest.admin.agents

import dev.gvart.genesara.admin.Admin
import dev.gvart.genesara.admin.AdminAuditLog
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.AgentBodyAdminGateway
import dev.gvart.genesara.world.AgentBodyAdminResult
import dev.gvart.genesara.world.AgentSafeNodeGateway
import dev.gvart.genesara.world.NodeId
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@RestController
@RequestMapping("/admin/agents/{agentId}")
internal class AgentBodyController(
    private val gateway: AgentBodyAdminGateway,
    private val safeNodes: AgentSafeNodeGateway,
    private val auditLog: AdminAuditLog,
    private val tick: TickClock,
) {

    @PostMapping("/gauges")
    fun setGauges(
        @AuthenticationPrincipal admin: Admin,
        @PathVariable agentId: UUID,
        @RequestBody req: GaugesRequest,
    ): GaugesResponse {
        if (req.isEmpty()) throw badRequest("at least one gauge field must be set")
        val agent = AgentId(agentId)
        val result = gateway.setGauges(
            agent = agent,
            hp = req.hp,
            stamina = req.stamina,
            mana = req.mana,
            hunger = req.hunger,
            thirst = req.thirst,
            sleep = req.sleep,
        )
        val applied = when (result) {
            is AgentBodyAdminResult.GaugesUpdated -> result.applied
            is AgentBodyAdminResult.AgentNotFound -> throw notFound("agent $agentId has no body row")
            else -> throw unexpected(result)
        }
        auditLog.record(
            adminId = admin.id,
            action = "agent.gauges",
            target = AUDIT_TARGET,
            targetId = agentId.toString(),
            payload = applied.mapValues { it.value as Any? },
            tick = tick.currentTick(),
        )
        return GaugesResponse(applied)
    }

    @PostMapping("/position")
    fun teleport(
        @AuthenticationPrincipal admin: Admin,
        @PathVariable agentId: UUID,
        @RequestBody req: PositionRequest,
    ): PositionResponse {
        val agent = AgentId(agentId)
        val nodeId = NodeId(req.nodeId)
        val result = gateway.teleport(agent, nodeId, tick.currentTick())
        val teleported = when (result) {
            is AgentBodyAdminResult.Teleported -> result
            is AgentBodyAdminResult.AgentNotFound -> throw notFound("agent $agentId is not registered")
            is AgentBodyAdminResult.NodeNotFound -> throw notFound("node ${req.nodeId} not found")
            else -> throw unexpected(result)
        }
        auditLog.record(
            adminId = admin.id,
            action = "agent.teleport",
            target = AUDIT_TARGET,
            targetId = agentId.toString(),
            payload = mapOf(
                "cause" to "admin",
                "to" to req.nodeId,
                "from" to teleported.from?.value,
                "crossedWorld" to teleported.crossedWorld,
            ),
            tick = tick.currentTick(),
        )
        return PositionResponse(
            from = teleported.from?.value,
            to = teleported.to.value,
            crossedWorld = teleported.crossedWorld,
        )
    }

    @PostMapping("/safe-node")
    fun setSafeNode(
        @AuthenticationPrincipal admin: Admin,
        @PathVariable agentId: UUID,
        @RequestBody req: SafeNodeRequest,
    ): SafeNodeResponse {
        val agent = AgentId(agentId)
        val currentTick = tick.currentTick()
        safeNodes.set(agent, NodeId(req.nodeId), currentTick)
        auditLog.record(
            adminId = admin.id,
            action = "agent.safe_node",
            target = AUDIT_TARGET,
            targetId = agentId.toString(),
            payload = mapOf("nodeId" to req.nodeId),
            tick = currentTick,
        )
        return SafeNodeResponse(req.nodeId)
    }

    @PostMapping("/respawn")
    fun respawn(
        @AuthenticationPrincipal admin: Admin,
        @PathVariable agentId: UUID,
    ): RespawnResponse {
        val agent = AgentId(agentId)
        val result = gateway.forceRespawn(agent, tick.currentTick())
        val respawned = when (result) {
            is AgentBodyAdminResult.Respawned -> result
            is AgentBodyAdminResult.AgentNotFound -> throw notFound("agent $agentId is not registered")
            is AgentBodyAdminResult.NoSpawnableNode -> throw conflict("no safe node and no spawnable fallback for $agentId")
            else -> throw unexpected(result)
        }
        auditLog.record(
            adminId = admin.id,
            action = "agent.force_respawn",
            target = AUDIT_TARGET,
            targetId = agentId.toString(),
            payload = mapOf(
                "at" to respawned.at.value,
                "fromCheckpoint" to respawned.fromCheckpoint,
            ),
            tick = tick.currentTick(),
        )
        return RespawnResponse(at = respawned.at.value, fromCheckpoint = respawned.fromCheckpoint)
    }

    private fun badRequest(detail: String) = ResponseStatusException(HttpStatus.BAD_REQUEST, detail)
    private fun notFound(detail: String) = ResponseStatusException(HttpStatus.NOT_FOUND, detail)
    private fun conflict(detail: String) = ResponseStatusException(HttpStatus.CONFLICT, detail)
    private fun unexpected(result: AgentBodyAdminResult) =
        ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "unexpected gateway result: $result")

    private companion object {
        const val AUDIT_TARGET = "agent"
    }
}

data class GaugesRequest(
    val hp: Int? = null,
    val stamina: Int? = null,
    val mana: Int? = null,
    val hunger: Int? = null,
    val thirst: Int? = null,
    val sleep: Int? = null,
) {
    fun isEmpty(): Boolean =
        hp == null && stamina == null && mana == null && hunger == null && thirst == null && sleep == null
}

data class GaugesResponse(val applied: Map<String, Int>)

data class PositionRequest(val nodeId: Long)

data class PositionResponse(val from: Long?, val to: Long, val crossedWorld: Boolean)

data class SafeNodeRequest(val nodeId: Long)

data class SafeNodeResponse(val nodeId: Long)

data class RespawnResponse(val at: Long, val fromCheckpoint: Boolean)
