package dev.gvart.genesara.api.internal.rest.admin.worlds

import dev.gvart.genesara.admin.Admin
import dev.gvart.genesara.admin.AdminAuditLog
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.Npc
import dev.gvart.genesara.world.NpcId
import dev.gvart.genesara.world.NpcType
import dev.gvart.genesara.world.NpcsStore
import dev.gvart.genesara.world.WorldId
import dev.gvart.genesara.world.WorldQueryGateway
import dev.gvart.genesara.world.environment.AdminNpcGateway
import dev.gvart.genesara.world.environment.AdminNpcGatewayError
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@RestController
@RequestMapping("/admin/worlds/{worldId}")
internal class NpcInstanceController(
    private val gateway: AdminNpcGateway,
    private val npcsStore: NpcsStore,
    private val queryGateway: WorldQueryGateway,
    private val auditLog: AdminAuditLog,
    private val tickClock: TickClock,
) {

    @PostMapping("/nodes/{nodeId}/npcs")
    fun spawn(
        @PathVariable worldId: Long,
        @PathVariable nodeId: Long,
        @Valid @RequestBody req: SpawnNpcRequest,
        @AuthenticationPrincipal admin: Admin,
    ): ResponseEntity<NpcDto> {
        requireNodeInWorld(WorldId(worldId), NodeId(nodeId))
        val tick = tickClock.currentTick()
        val npc = try {
            gateway.spawn(NodeId(nodeId), NpcType(req.type), req.hp, tick)
        } catch (e: AdminNpcGatewayError.UnknownType) {
            throw badRequest("unknown NPC type ${e.type.value}")
        } catch (e: AdminNpcGatewayError.InvalidHp) {
            throw badRequest("hp (${e.requested}) must be in 1..${e.hpMax}")
        }
        auditLog.record(
            adminId = admin.id,
            action = "npc.spawn",
            target = "npc",
            targetId = npc.id.value.toString(),
            payload = mapOf(
                "worldId" to worldId,
                "nodeId" to nodeId,
                "type" to npc.type.value,
                "hp" to npc.hpCurrent,
            ),
            tick = tick,
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(npc.toDto())
    }

    @GetMapping("/nodes/{nodeId}/npcs")
    fun listAtNode(
        @PathVariable worldId: Long,
        @PathVariable nodeId: Long,
    ): List<NpcDto> {
        requireNodeInWorld(WorldId(worldId), NodeId(nodeId))
        return gateway.listAtNode(NodeId(nodeId)).map(Npc::toDto)
    }

    @PatchMapping("/npcs/{npcId}")
    fun update(
        @PathVariable worldId: Long,
        @PathVariable npcId: UUID,
        @Valid @RequestBody req: UpdateNpcRequest,
        @AuthenticationPrincipal admin: Admin,
    ): NpcDto {
        val target = NpcId(npcId)
        val existing = npcsStore.findById(target) ?: throw notFound("npc $npcId not found")
        requireNodeInWorld(WorldId(worldId), existing.nodeId)
        if (req.nodeId != null) requireNodeInWorld(WorldId(worldId), NodeId(req.nodeId))
        val tick = tickClock.currentTick()
        val updated = try {
            gateway.update(target, req.hp, req.nodeId?.let(::NodeId), tick)
        } catch (e: AdminNpcGatewayError.NotFound) {
            throw notFound("npc ${e.npcId.value} not found")
        } catch (e: AdminNpcGatewayError.InvalidHp) {
            throw badRequest("hp (${e.requested}) must be in 1..${e.hpMax}")
        }
        val payload = mutableMapOf<String, Any?>(
            "worldId" to worldId,
            "npcId" to npcId.toString(),
        )
        if (req.hp != null) payload["hp"] = req.hp
        if (req.nodeId != null) payload["nodeId"] = req.nodeId
        auditLog.record(
            adminId = admin.id,
            action = "npc.update",
            target = "npc",
            targetId = npcId.toString(),
            payload = payload,
            tick = tick,
        )
        return updated.toDto()
    }

    @DeleteMapping("/npcs/{npcId}")
    fun delete(
        @PathVariable worldId: Long,
        @PathVariable npcId: UUID,
        @RequestParam(name = "silent", defaultValue = "false") silent: Boolean,
        @AuthenticationPrincipal admin: Admin,
    ): ResponseEntity<Void> {
        val target = NpcId(npcId)
        val existing = npcsStore.findById(target) ?: throw notFound("npc $npcId not found")
        requireNodeInWorld(WorldId(worldId), existing.nodeId)
        val tick = tickClock.currentTick()
        val outcome = gateway.kill(target, silent, tick)
        auditLog.record(
            adminId = admin.id,
            action = "npc.kill",
            target = "npc",
            targetId = npcId.toString(),
            payload = mapOf(
                "worldId" to worldId,
                "type" to outcome.npc.type.value,
                "nodeId" to outcome.npc.nodeId.value,
                "silent" to silent,
                "dropCount" to outcome.drops.size,
            ),
            tick = tick,
        )
        return ResponseEntity.noContent().build()
    }

    private fun requireNodeInWorld(worldId: WorldId, nodeId: NodeId) {
        val node = queryGateway.node(nodeId) ?: throw notFound("node ${nodeId.value} not found")
        val region = queryGateway.region(node.regionId)
            ?: throw notFound("node ${nodeId.value} has no region")
        if (region.worldId != worldId) {
            throw notFound("node ${nodeId.value} does not belong to world ${worldId.value}")
        }
    }

    private fun badRequest(detail: String) = ResponseStatusException(HttpStatus.BAD_REQUEST, detail)
    private fun notFound(detail: String) = ResponseStatusException(HttpStatus.NOT_FOUND, detail)
}

data class SpawnNpcRequest(
    @field:NotBlank val type: String,
    @field:Positive val hp: Int? = null,
)

data class UpdateNpcRequest(
    @field:Positive val hp: Int? = null,
    val nodeId: Long? = null,
)

data class NpcDto(
    val id: UUID,
    val type: String,
    val nodeId: Long,
    val spawnNodeId: Long,
    val hpCurrent: Int,
    val hpMax: Int,
    val spawnedAtTick: Long,
    val lastAttackTick: Long,
)

private fun Npc.toDto() = NpcDto(
    id = id.value,
    type = type.value,
    nodeId = nodeId.value,
    spawnNodeId = spawnNodeId.value,
    hpCurrent = hpCurrent,
    hpMax = hpMax,
    spawnedAtTick = spawnedAtTick,
    lastAttackTick = lastAttackTick,
)
