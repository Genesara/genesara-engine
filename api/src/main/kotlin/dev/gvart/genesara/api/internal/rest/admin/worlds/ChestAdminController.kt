package dev.gvart.genesara.api.internal.rest.admin.worlds

import dev.gvart.genesara.admin.Admin
import dev.gvart.genesara.admin.AdminAuditLog
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.world.Building
import dev.gvart.genesara.world.BuildingsStore
import dev.gvart.genesara.world.ChestContentsStore
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.WorldId
import dev.gvart.genesara.world.WorldQueryGateway
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@RestController
@RequestMapping("/admin/worlds/{worldId}/buildings/{instanceId}/chest")
internal class ChestAdminController(
    private val buildings: BuildingsStore,
    private val chestContents: ChestContentsStore,
    private val world: WorldQueryGateway,
    private val auditLog: AdminAuditLog,
    private val tick: TickClock,
) {

    @GetMapping
    fun get(
        @PathVariable worldId: Long,
        @PathVariable instanceId: UUID,
    ): ChestContentsDto {
        val chest = resolveChest(WorldId(worldId), instanceId)
        return chest.toDto(chestContents.contentsOf(instanceId))
    }

    @PutMapping
    fun replace(
        @PathVariable worldId: Long,
        @PathVariable instanceId: UUID,
        @RequestBody req: ReplaceChestContentsRequest,
        @AuthenticationPrincipal admin: Admin,
    ): ChestContentsDto {
        val chest = resolveChest(WorldId(worldId), instanceId)
        req.contents.forEach { (itemId, qty) ->
            if (qty <= 0) throw badRequest("quantity for $itemId ($qty) must be positive")
        }
        val typed = req.contents.mapKeys { (id, _) -> ItemId(id) }
        chestContents.replace(instanceId, typed)
        audit(admin, "chest.replace", instanceId, mapOf(
            "worldId" to worldId,
            "nodeId" to chest.nodeId.value,
            "contents" to req.contents,
        ))
        return chest.toDto(chestContents.contentsOf(instanceId))
    }

    @PatchMapping
    fun patch(
        @PathVariable worldId: Long,
        @PathVariable instanceId: UUID,
        @RequestBody req: PatchChestContentsRequest,
        @AuthenticationPrincipal admin: Admin,
    ): ChestContentsDto {
        val chest = resolveChest(WorldId(worldId), instanceId)
        if (req.delta == 0) throw badRequest("delta must be non-zero")
        val item = ItemId(req.itemId)
        if (req.delta > 0) {
            chestContents.add(instanceId, item, req.delta)
        } else {
            val removed = chestContents.remove(instanceId, item, -req.delta)
            if (!removed) {
                val have = chestContents.quantityOf(instanceId, item)
                throw badRequest("cannot remove ${-req.delta} of ${req.itemId}: chest holds $have")
            }
        }
        audit(admin, "chest.patch", instanceId, mapOf(
            "worldId" to worldId,
            "nodeId" to chest.nodeId.value,
            "itemId" to req.itemId,
            "delta" to req.delta,
        ))
        return chest.toDto(chestContents.contentsOf(instanceId))
    }

    @DeleteMapping("/{itemId}")
    fun delete(
        @PathVariable worldId: Long,
        @PathVariable instanceId: UUID,
        @PathVariable itemId: String,
        @AuthenticationPrincipal admin: Admin,
    ): ResponseEntity<Void> {
        val chest = resolveChest(WorldId(worldId), instanceId)
        chestContents.removeAll(instanceId, ItemId(itemId))
        audit(admin, "chest.delete", instanceId, mapOf(
            "worldId" to worldId,
            "nodeId" to chest.nodeId.value,
            "itemId" to itemId,
        ))
        return ResponseEntity.noContent().build()
    }

    private fun resolveChest(worldId: WorldId, instanceId: UUID): Building {
        val building = buildings.findById(instanceId)
            ?: throw notFound("building $instanceId not found")
        requireNodeInWorld(worldId, building.nodeId)
        if (!building.type.holdsChest()) {
            throw badRequest("building ${building.type} does not hold a chest")
        }
        return building
    }

    private fun requireNodeInWorld(worldId: WorldId, nodeId: NodeId) {
        val node = world.node(nodeId)
            ?: throw notFound("node ${nodeId.value} not found in world ${worldId.value}")
        val region = world.region(node.regionId)
            ?: throw notFound("node ${nodeId.value} not found in world ${worldId.value}")
        if (region.worldId != worldId) {
            throw notFound("node ${nodeId.value} not found in world ${worldId.value}")
        }
    }

    private fun audit(admin: Admin, action: String, instanceId: UUID, payload: Map<String, Any?>) {
        auditLog.record(
            adminId = admin.id,
            action = action,
            target = "chest",
            targetId = instanceId.toString(),
            payload = payload,
            tick = tick.currentTick(),
        )
    }

    private fun Building.toDto(contents: Map<ItemId, Int>) = ChestContentsDto(
        instanceId = instanceId,
        nodeId = nodeId.value,
        contents = contents.mapKeys { (id, _) -> id.value },
    )

    private fun notFound(detail: String) = ResponseStatusException(HttpStatus.NOT_FOUND, detail)
    private fun badRequest(detail: String) = ResponseStatusException(HttpStatus.BAD_REQUEST, detail)
}
