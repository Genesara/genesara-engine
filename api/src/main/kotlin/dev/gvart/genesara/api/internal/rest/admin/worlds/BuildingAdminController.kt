package dev.gvart.genesara.api.internal.rest.admin.worlds

import dev.gvart.genesara.admin.Admin
import dev.gvart.genesara.admin.AdminAuditLog
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.player.AdminSentinel
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.Building
import dev.gvart.genesara.world.BuildingDefLookup
import dev.gvart.genesara.world.BuildingStatus
import dev.gvart.genesara.world.BuildingsStore
import dev.gvart.genesara.world.MaybeSet
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.WorldId
import dev.gvart.genesara.world.WorldQueryGateway
import dev.gvart.genesara.world.events.EnvironmentEvent
import org.springframework.context.ApplicationEventPublisher
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
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@RestController
@RequestMapping("/admin/worlds/{worldId}")
internal class BuildingAdminController(
    private val store: BuildingsStore,
    private val world: WorldQueryGateway,
    private val catalog: BuildingDefLookup,
    private val tick: TickClock,
    private val auditLog: AdminAuditLog,
    private val publisher: ApplicationEventPublisher,
) {

    @GetMapping("/nodes/{nodeId}/buildings")
    fun list(
        @PathVariable worldId: Long,
        @PathVariable nodeId: Long,
    ): List<BuildingDto> {
        requireNodeInWorld(WorldId(worldId), NodeId(nodeId))
        return store.listAtNode(NodeId(nodeId)).map { it.toDto() }
    }

    @PostMapping("/nodes/{nodeId}/buildings")
    fun create(
        @PathVariable worldId: Long,
        @PathVariable nodeId: Long,
        @RequestBody req: CreateBuildingRequest,
        @AuthenticationPrincipal admin: Admin,
    ): ResponseEntity<BuildingDto> {
        requireNodeInWorld(WorldId(worldId), NodeId(nodeId))
        val def = catalog.byType(req.type)
            ?: throw badRequest("building type ${req.type} is not in the catalog")
        val now = tick.currentTick()
        val totalSteps = req.totalSteps ?: def.totalSteps
        val hpMax = req.hpMax ?: def.hp
        val progressSteps = req.progressSteps ?: totalSteps
        val status = req.status ?: BuildingStatus.ACTIVE
        val hpCurrent = req.hpCurrent ?: hpMax
        val builtBy = req.builtByAgentId?.let(::AgentId) ?: AdminSentinel.agentId

        validateInvariants(
            status = status,
            progressSteps = progressSteps,
            totalSteps = totalSteps,
            hpCurrent = hpCurrent,
            hpMax = hpMax,
        )

        val building = Building(
            instanceId = UUID.randomUUID(),
            nodeId = NodeId(nodeId),
            type = req.type,
            status = status,
            builtByAgentId = builtBy,
            builtAtTick = now,
            lastProgressTick = now,
            progressSteps = progressSteps,
            totalSteps = totalSteps,
            hpCurrent = hpCurrent,
            hpMax = hpMax,
        )
        store.insert(building)

        val changedFields = mutableSetOf<String>().apply {
            if (req.status != null) add("status")
            if (req.hpCurrent != null) add("hpCurrent")
            if (req.hpMax != null) add("hpMax")
            if (req.progressSteps != null) add("progressSteps")
            if (req.totalSteps != null) add("totalSteps")
            if (req.builtByAgentId != null) add("builtByAgentId")
        }
        emitAdminEdited(building, removed = false, changedFields = changedFields, adminId = admin.id.id)
        auditCreate(admin, building)

        return ResponseEntity.status(HttpStatus.CREATED).body(building.toDto())
    }

    @PatchMapping("/buildings/{instanceId}")
    fun patch(
        @PathVariable worldId: Long,
        @PathVariable instanceId: UUID,
        @RequestBody req: PatchBuildingRequest,
        @AuthenticationPrincipal admin: Admin,
    ): BuildingDto {
        val existing = store.findById(instanceId) ?: throw notFound("building $instanceId not found")
        requireNodeInWorld(WorldId(worldId), existing.nodeId)

        val newStatus = req.status.resolveOrCurrent(existing.status) {
            it ?: throw badRequest("status cannot be cleared")
        }
        val newHpCurrent = req.hpCurrent.resolveOrCurrent(existing.hpCurrent) {
            it ?: throw badRequest("hpCurrent cannot be cleared")
        }
        val newHpMax = req.hpMax.resolveOrCurrent(existing.hpMax) {
            it ?: throw badRequest("hpMax cannot be cleared")
        }
        val newProgressSteps = req.progressSteps.resolveOrCurrent(existing.progressSteps) {
            it ?: throw badRequest("progressSteps cannot be cleared")
        }
        val newTotalSteps = req.totalSteps.resolveOrCurrent(existing.totalSteps) {
            it ?: throw badRequest("totalSteps cannot be cleared")
        }
        val newBuiltBy = req.builtByAgentId.resolveOrCurrent(existing.builtByAgentId) {
            (it ?: throw badRequest("builtByAgentId cannot be cleared")).let(::AgentId)
        }

        validateInvariants(
            status = newStatus,
            progressSteps = newProgressSteps,
            totalSteps = newTotalSteps,
            hpCurrent = newHpCurrent,
            hpMax = newHpMax,
        )

        val now = tick.currentTick()
        val updated = existing.copy(
            status = newStatus,
            builtByAgentId = newBuiltBy,
            lastProgressTick = now,
            progressSteps = newProgressSteps,
            totalSteps = newTotalSteps,
            hpCurrent = newHpCurrent,
            hpMax = newHpMax,
        )
        val persisted = store.update(updated)
            ?: throw notFound("building $instanceId not found")

        val changedFields = mutableSetOf<String>().apply {
            if (req.status is MaybeSet.Set) add("status")
            if (req.hpCurrent is MaybeSet.Set) add("hpCurrent")
            if (req.hpMax is MaybeSet.Set) add("hpMax")
            if (req.progressSteps is MaybeSet.Set) add("progressSteps")
            if (req.totalSteps is MaybeSet.Set) add("totalSteps")
            if (req.builtByAgentId is MaybeSet.Set) add("builtByAgentId")
        }
        emitAdminEdited(persisted, removed = false, changedFields = changedFields, adminId = admin.id.id)
        auditPatch(admin, persisted, changedFields)

        return persisted.toDto()
    }

    @DeleteMapping("/buildings/{instanceId}")
    fun delete(
        @PathVariable worldId: Long,
        @PathVariable instanceId: UUID,
        @AuthenticationPrincipal admin: Admin,
    ): ResponseEntity<Void> {
        val existing = store.findById(instanceId) ?: throw notFound("building $instanceId not found")
        requireNodeInWorld(WorldId(worldId), existing.nodeId)
        val removed = store.delete(instanceId)
        if (!removed) throw notFound("building $instanceId not found")

        emitAdminEdited(existing, removed = true, changedFields = emptySet(), adminId = admin.id.id)
        auditDelete(admin, existing)
        return ResponseEntity.noContent().build()
    }

    private fun validateInvariants(
        status: BuildingStatus,
        progressSteps: Int,
        totalSteps: Int,
        hpCurrent: Int,
        hpMax: Int,
    ) {
        if (totalSteps <= 0) throw badRequest("totalSteps ($totalSteps) must be positive")
        if (progressSteps < 0 || progressSteps > totalSteps) {
            throw badRequest("progressSteps ($progressSteps) must be in 0..totalSteps ($totalSteps)")
        }
        if (hpMax <= 0) throw badRequest("hpMax ($hpMax) must be positive")
        if (hpCurrent < 0 || hpCurrent > hpMax) {
            throw badRequest("hpCurrent ($hpCurrent) must be in 0..hpMax ($hpMax)")
        }
        val isActive = status == BuildingStatus.ACTIVE
        val isComplete = progressSteps == totalSteps
        if (isActive != isComplete) {
            throw badRequest(
                "status ($status) must match completion " +
                    "(progressSteps=$progressSteps, totalSteps=$totalSteps)"
            )
        }
    }

    private fun requireNodeInWorld(worldId: WorldId, nodeId: NodeId) {
        val node = world.node(nodeId) ?: throw notFound("node ${nodeId.value} not found in world ${worldId.value}")
        val region = world.region(node.regionId)
            ?: throw notFound("node ${nodeId.value} not found in world ${worldId.value}")
        if (region.worldId != worldId) {
            throw notFound("node ${nodeId.value} not found in world ${worldId.value}")
        }
    }

    private fun emitAdminEdited(
        building: Building,
        removed: Boolean,
        changedFields: Set<String>,
        adminId: UUID,
    ) {
        publisher.publishEvent(
            EnvironmentEvent.BuildingAdminEdited(
                instanceId = building.instanceId,
                type = building.type,
                at = building.nodeId,
                status = building.status,
                hpCurrent = building.hpCurrent,
                hpMax = building.hpMax,
                progressSteps = building.progressSteps,
                totalSteps = building.totalSteps,
                removed = removed,
                changedFields = changedFields,
                byAdminId = adminId,
                tick = tick.currentTick(),
            )
        )
    }

    private fun auditCreate(admin: Admin, building: Building) {
        auditLog.record(
            adminId = admin.id,
            action = "building.create",
            target = "building",
            targetId = building.instanceId.toString(),
            payload = building.toAuditPayload(),
            tick = building.lastProgressTick,
        )
    }

    private fun auditPatch(admin: Admin, building: Building, changedFields: Set<String>) {
        auditLog.record(
            adminId = admin.id,
            action = "building.patch",
            target = "building",
            targetId = building.instanceId.toString(),
            payload = building.toAuditPayload() + ("changedFields" to changedFields.toList()),
            tick = building.lastProgressTick,
        )
    }

    private fun auditDelete(admin: Admin, building: Building) {
        auditLog.record(
            adminId = admin.id,
            action = "building.delete",
            target = "building",
            targetId = building.instanceId.toString(),
            payload = building.toAuditPayload(),
            tick = tick.currentTick(),
        )
    }

    private fun Building.toAuditPayload(): Map<String, Any?> = mapOf(
        "instanceId" to instanceId.toString(),
        "nodeId" to nodeId.value,
        "type" to type.name,
        "status" to status.name,
        "hpCurrent" to hpCurrent,
        "hpMax" to hpMax,
        "progressSteps" to progressSteps,
        "totalSteps" to totalSteps,
        "builtByAgentId" to builtByAgentId.id.toString(),
    )

    private fun notFound(detail: String) = ResponseStatusException(HttpStatus.NOT_FOUND, detail)
    private fun badRequest(detail: String) = ResponseStatusException(HttpStatus.BAD_REQUEST, detail)
}

private inline fun <T, R> MaybeSet<T?>.resolveOrCurrent(current: R, transform: (T?) -> R): R = when (this) {
    is MaybeSet.Skip -> current
    is MaybeSet.Set -> transform(value)
}

private fun Building.toDto() = BuildingDto(
    instanceId = instanceId,
    nodeId = nodeId.value,
    type = type,
    status = status,
    builtByAgentId = builtByAgentId.id,
    builtAtTick = builtAtTick,
    lastProgressTick = lastProgressTick,
    progressSteps = progressSteps,
    totalSteps = totalSteps,
    hpCurrent = hpCurrent,
    hpMax = hpMax,
)
