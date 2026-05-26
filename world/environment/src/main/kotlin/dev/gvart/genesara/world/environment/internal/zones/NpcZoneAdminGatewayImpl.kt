package dev.gvart.genesara.world.environment.internal.zones

import dev.gvart.genesara.world.MaybeSet
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.NpcCatalog
import dev.gvart.genesara.world.NpcType
import dev.gvart.genesara.world.NpcZone
import dev.gvart.genesara.world.NpcZoneAdminError
import dev.gvart.genesara.world.NpcZoneAdminGateway
import dev.gvart.genesara.world.NpcZoneCreateSpec
import dev.gvart.genesara.world.NpcZonePatch
import dev.gvart.genesara.world.NpcZoneScope
import dev.gvart.genesara.world.RegionId
import dev.gvart.genesara.world.WorldId
import dev.gvart.genesara.world.WorldQueryGateway
import org.springframework.stereotype.Component
import java.util.UUID

@Component
internal class NpcZoneAdminGatewayImpl(
    private val store: NpcZonesStore,
    private val catalog: NpcCatalog,
    private val world: WorldQueryGateway,
) : NpcZoneAdminGateway {

    override fun list(worldId: WorldId): List<NpcZone> = store.listByWorld(worldId)

    override fun create(spec: NpcZoneCreateSpec): NpcZone {
        validateScope(spec.scope, spec.regionId, spec.nodeId)
        validateWeights(spec.weights)
        if (spec.maxConcurrent < 0) throw NpcZoneAdminError.NegativeMaxConcurrent(spec.maxConcurrent)
        spec.respawnTicks?.let { if (it < 0) throw NpcZoneAdminError.NegativeRespawnTicks(it) }
        when (spec.scope) {
            NpcZoneScope.REGION -> requireRegionInWorld(spec.worldId, spec.regionId!!)
            NpcZoneScope.NODE -> requireNodeInWorld(spec.worldId, spec.nodeId!!)
        }
        val zone = NpcZone(
            zoneId = UUID.randomUUID(),
            worldId = spec.worldId,
            scope = spec.scope,
            regionId = spec.regionId,
            nodeId = spec.nodeId,
            weights = spec.weights,
            maxConcurrent = spec.maxConcurrent,
            respawnTicks = spec.respawnTicks,
            active = spec.active,
            createdBy = spec.createdBy,
            createdAtTick = spec.createdAtTick,
        )
        store.insert(zone)
        return zone
    }

    override fun patch(zoneId: UUID, patch: NpcZonePatch): NpcZone {
        val existing = store.findById(zoneId) ?: throw NpcZoneAdminError.NotFound(zoneId)
        val weightsPatch = patch.weights
        val newWeights = when (weightsPatch) {
            is MaybeSet.Skip -> existing.weights
            is MaybeSet.Set -> weightsPatch.value.also(::validateWeights)
        }
        val maxConcurrentPatch = patch.maxConcurrent
        val newMaxConcurrent = when (maxConcurrentPatch) {
            is MaybeSet.Skip -> existing.maxConcurrent
            is MaybeSet.Set -> maxConcurrentPatch.value.also {
                if (it < 0) throw NpcZoneAdminError.NegativeMaxConcurrent(it)
            }
        }
        val respawnTicksPatch = patch.respawnTicks
        val newRespawnTicks = when (respawnTicksPatch) {
            is MaybeSet.Skip -> existing.respawnTicks
            is MaybeSet.Set -> respawnTicksPatch.value?.also {
                if (it < 0) throw NpcZoneAdminError.NegativeRespawnTicks(it)
            }
        }
        val activePatch = patch.active
        val newActive = when (activePatch) {
            is MaybeSet.Skip -> existing.active
            is MaybeSet.Set -> activePatch.value
        }
        val updated = existing.copy(
            weights = newWeights,
            maxConcurrent = newMaxConcurrent,
            respawnTicks = newRespawnTicks,
            active = newActive,
        )
        return store.update(updated) ?: throw NpcZoneAdminError.NotFound(zoneId)
    }

    override fun delete(zoneId: UUID): Boolean = store.delete(zoneId)

    private fun validateScope(scope: NpcZoneScope, regionId: RegionId?, nodeId: NodeId?) {
        when (scope) {
            NpcZoneScope.REGION ->
                if (regionId == null || nodeId != null) {
                    throw NpcZoneAdminError.ScopeMismatch(
                        "REGION scope requires regionId set and nodeId null"
                    )
                }
            NpcZoneScope.NODE ->
                if (nodeId == null || regionId != null) {
                    throw NpcZoneAdminError.ScopeMismatch(
                        "NODE scope requires nodeId set and regionId null"
                    )
                }
        }
    }

    private fun validateWeights(weights: Map<NpcType, Int>) {
        if (weights.isEmpty()) throw NpcZoneAdminError.EmptyWeights()
        val unknown = weights.keys.filter { catalog.byType(it) == null }.toSet()
        if (unknown.isNotEmpty()) throw NpcZoneAdminError.UnknownNpcTypes(unknown)
        for ((type, weight) in weights) {
            if (weight <= 0) throw NpcZoneAdminError.NonPositiveWeight(type, weight)
        }
    }

    private fun requireNodeInWorld(worldId: WorldId, nodeId: NodeId) {
        val node = world.node(nodeId)
            ?: throw NpcZoneAdminError.WorldMismatch(expected = worldId, actual = null)
        val region = world.region(node.regionId)
            ?: throw NpcZoneAdminError.WorldMismatch(expected = worldId, actual = null)
        if (region.worldId != worldId) {
            throw NpcZoneAdminError.WorldMismatch(expected = worldId, actual = region.worldId)
        }
    }

    private fun requireRegionInWorld(worldId: WorldId, regionId: RegionId) {
        val region = world.region(regionId)
            ?: throw NpcZoneAdminError.WorldMismatch(expected = worldId, actual = null)
        if (region.worldId != worldId) {
            throw NpcZoneAdminError.WorldMismatch(expected = worldId, actual = region.worldId)
        }
    }
}
