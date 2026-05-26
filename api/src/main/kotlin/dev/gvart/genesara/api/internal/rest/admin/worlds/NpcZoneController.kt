package dev.gvart.genesara.api.internal.rest.admin.worlds

import dev.gvart.genesara.admin.Admin
import dev.gvart.genesara.admin.AdminAuditLog
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.world.MaybeSet
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.NpcType
import dev.gvart.genesara.world.NpcZone
import dev.gvart.genesara.world.NpcZoneAdminError
import dev.gvart.genesara.world.NpcZoneAdminGateway
import dev.gvart.genesara.world.NpcZoneCreateSpec
import dev.gvart.genesara.world.NpcZonePatch
import dev.gvart.genesara.world.NpcZoneScope
import dev.gvart.genesara.world.RegionId
import dev.gvart.genesara.world.WorldId
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
@RequestMapping("/admin/worlds/{worldId}/npc-zones")
internal class NpcZoneController(
    private val gateway: NpcZoneAdminGateway,
    private val auditLog: AdminAuditLog,
    private val tickClock: TickClock,
) {

    @GetMapping
    fun list(@PathVariable worldId: Long): List<NpcZoneDto> =
        gateway.list(WorldId(worldId)).map(NpcZone::toDto)

    @PostMapping
    fun create(
        @PathVariable worldId: Long,
        @RequestBody req: CreateNpcZoneRequest,
        @AuthenticationPrincipal admin: Admin,
    ): ResponseEntity<NpcZoneDto> {
        val tick = tickClock.currentTick()
        val spec = NpcZoneCreateSpec(
            worldId = WorldId(worldId),
            scope = req.scope,
            regionId = req.regionId?.let(::RegionId),
            nodeId = req.nodeId?.let(::NodeId),
            weights = req.weights.mapKeys { NpcType(it.key) },
            maxConcurrent = req.maxConcurrent,
            respawnTicks = req.respawnTicks,
            active = req.active,
            createdBy = admin.id.id,
            createdAtTick = tick,
        )
        val zone = create(spec)
        auditLog.record(
            adminId = admin.id,
            action = "npc-zone.create",
            target = "npc-zone",
            targetId = zone.zoneId.toString(),
            payload = zone.toAuditPayload(),
            tick = tick,
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(zone.toDto())
    }

    @PatchMapping("/{zoneId}")
    fun patch(
        @PathVariable worldId: Long,
        @PathVariable zoneId: UUID,
        @RequestBody req: PatchNpcZoneRequest,
        @AuthenticationPrincipal admin: Admin,
    ): NpcZoneDto {
        val patch = NpcZonePatch(
            weights = req.weights.map { it.mapKeys { e -> NpcType(e.key) } },
            maxConcurrent = req.maxConcurrent,
            respawnTicks = req.respawnTicks,
            active = req.active,
        )
        val updated = applyPatch(zoneId, patch)
        requireZoneInWorld(WorldId(worldId), updated)
        val tick = tickClock.currentTick()
        val changedFields = mutableSetOf<String>().apply {
            if (req.weights is MaybeSet.Set) add("weights")
            if (req.maxConcurrent is MaybeSet.Set) add("maxConcurrent")
            if (req.respawnTicks is MaybeSet.Set) add("respawnTicks")
            if (req.active is MaybeSet.Set) add("active")
        }
        auditLog.record(
            adminId = admin.id,
            action = "npc-zone.patch",
            target = "npc-zone",
            targetId = zoneId.toString(),
            payload = updated.toAuditPayload() + ("changedFields" to changedFields.toList()),
            tick = tick,
        )
        return updated.toDto()
    }

    @DeleteMapping("/{zoneId}")
    fun delete(
        @PathVariable worldId: Long,
        @PathVariable zoneId: UUID,
        @AuthenticationPrincipal admin: Admin,
    ): ResponseEntity<Void> {
        val existing = gateway.list(WorldId(worldId)).firstOrNull { it.zoneId == zoneId }
            ?: throw notFound("npc zone $zoneId not found in world $worldId")
        val removed = gateway.delete(zoneId)
        if (!removed) throw notFound("npc zone $zoneId not found")
        auditLog.record(
            adminId = admin.id,
            action = "npc-zone.delete",
            target = "npc-zone",
            targetId = zoneId.toString(),
            payload = existing.toAuditPayload(),
            tick = tickClock.currentTick(),
        )
        return ResponseEntity.noContent().build()
    }

    private fun create(spec: NpcZoneCreateSpec): NpcZone =
        try {
            gateway.create(spec)
        } catch (e: NpcZoneAdminError.UnknownNpcTypes) {
            throw badRequest("unknown NPC types: ${e.types.joinToString { it.value }}")
        } catch (e: NpcZoneAdminError.WorldMismatch) {
            throw notFound(e.message ?: "target not found")
        } catch (e: NpcZoneAdminError.ScopeMismatch) {
            throw badRequest(e.message ?: "scope mismatch")
        } catch (e: NpcZoneAdminError.EmptyWeights) {
            throw badRequest("weights must contain at least one entry")
        } catch (e: NpcZoneAdminError.NonPositiveWeight) {
            throw badRequest("weight for ${e.type.value} must be positive (was ${e.weight})")
        } catch (e: NpcZoneAdminError.NegativeMaxConcurrent) {
            throw badRequest("maxConcurrent must be >= 0 (was ${e.value})")
        } catch (e: NpcZoneAdminError.NegativeRespawnTicks) {
            throw badRequest("respawnTicks must be >= 0 (was ${e.value})")
        }

    private fun applyPatch(zoneId: UUID, patch: NpcZonePatch): NpcZone =
        try {
            gateway.patch(zoneId, patch)
        } catch (e: NpcZoneAdminError.NotFound) {
            throw notFound("npc zone $zoneId not found")
        } catch (e: NpcZoneAdminError.UnknownNpcTypes) {
            throw badRequest("unknown NPC types: ${e.types.joinToString { it.value }}")
        } catch (e: NpcZoneAdminError.EmptyWeights) {
            throw badRequest("weights must contain at least one entry")
        } catch (e: NpcZoneAdminError.NonPositiveWeight) {
            throw badRequest("weight for ${e.type.value} must be positive (was ${e.weight})")
        } catch (e: NpcZoneAdminError.NegativeMaxConcurrent) {
            throw badRequest("maxConcurrent must be >= 0 (was ${e.value})")
        } catch (e: NpcZoneAdminError.NegativeRespawnTicks) {
            throw badRequest("respawnTicks must be >= 0 (was ${e.value})")
        }

    private fun requireZoneInWorld(worldId: WorldId, zone: NpcZone) {
        if (zone.worldId != worldId) {
            throw notFound("npc zone ${zone.zoneId} not found in world ${worldId.value}")
        }
    }

    private fun badRequest(detail: String) = ResponseStatusException(HttpStatus.BAD_REQUEST, detail)
    private fun notFound(detail: String) = ResponseStatusException(HttpStatus.NOT_FOUND, detail)
}

private fun NpcZone.toDto() = NpcZoneDto(
    zoneId = zoneId,
    worldId = worldId.value,
    scope = scope,
    regionId = regionId?.value,
    nodeId = nodeId?.value,
    weights = weights.mapKeys { it.key.value },
    maxConcurrent = maxConcurrent,
    respawnTicks = respawnTicks,
    active = active,
    createdBy = createdBy,
    createdAtTick = createdAtTick,
)

private fun NpcZone.toAuditPayload(): Map<String, Any?> = mapOf(
    "zoneId" to zoneId.toString(),
    "worldId" to worldId.value,
    "scope" to scope.name,
    "regionId" to regionId?.value,
    "nodeId" to nodeId?.value,
    "weights" to weights.mapKeys { it.key.value },
    "maxConcurrent" to maxConcurrent,
    "respawnTicks" to respawnTicks,
    "active" to active,
)

private inline fun <T, R> MaybeSet<T>.map(transform: (T) -> R): MaybeSet<R> = when (this) {
    is MaybeSet.Skip -> MaybeSet.Skip
    is MaybeSet.Set -> MaybeSet.Set(transform(value))
}
