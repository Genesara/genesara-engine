package dev.gvart.genesara.world.environment.internal.zones

import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.NpcType
import dev.gvart.genesara.world.NpcZone
import dev.gvart.genesara.world.NpcZoneScope
import dev.gvart.genesara.world.RegionId
import dev.gvart.genesara.world.WorldId
import dev.gvart.genesara.world.internal.jooq.tables.records.NpcZonesRecord
import dev.gvart.genesara.world.internal.jooq.tables.references.NPC_ZONES
import org.jooq.DSLContext
import org.jooq.JSON
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import java.util.UUID

@Component
internal class JooqNpcZonesStore(
    private val dsl: DSLContext,
    private val mapper: ObjectMapper,
) : NpcZonesStore {

    @Transactional(readOnly = true)
    override fun listByWorld(worldId: WorldId): List<NpcZone> =
        dsl.selectFrom(NPC_ZONES)
            .where(NPC_ZONES.WORLD_ID.eq(worldId.value))
            .orderBy(NPC_ZONES.CREATED_AT_TICK.asc())
            .fetch(::toDomain)

    @Transactional(readOnly = true)
    override fun findById(zoneId: UUID): NpcZone? =
        dsl.selectFrom(NPC_ZONES)
            .where(NPC_ZONES.ZONE_ID.eq(zoneId))
            .fetchOne(::toDomain)

    @Transactional(readOnly = true)
    override fun findActiveByNode(nodeId: NodeId): NpcZone? =
        dsl.selectFrom(NPC_ZONES)
            .where(NPC_ZONES.NODE_ID.eq(nodeId.value))
            .and(NPC_ZONES.ACTIVE.eq(true))
            .orderBy(NPC_ZONES.CREATED_AT_TICK.desc())
            .limit(1)
            .fetchOne(::toDomain)

    @Transactional(readOnly = true)
    override fun findActiveByRegion(regionId: RegionId): NpcZone? =
        dsl.selectFrom(NPC_ZONES)
            .where(NPC_ZONES.REGION_ID.eq(regionId.value))
            .and(NPC_ZONES.ACTIVE.eq(true))
            .orderBy(NPC_ZONES.CREATED_AT_TICK.desc())
            .limit(1)
            .fetchOne(::toDomain)

    @Transactional
    override fun insert(zone: NpcZone) {
        dsl.insertInto(NPC_ZONES)
            .set(NPC_ZONES.ZONE_ID, zone.zoneId)
            .set(NPC_ZONES.WORLD_ID, zone.worldId.value)
            .set(NPC_ZONES.SCOPE, zone.scope.name)
            .set(NPC_ZONES.REGION_ID, zone.regionId?.value)
            .set(NPC_ZONES.NODE_ID, zone.nodeId?.value)
            .set(NPC_ZONES.WEIGHTS, encodeWeights(zone.weights))
            .set(NPC_ZONES.MAX_CONCURRENT, zone.maxConcurrent)
            .set(NPC_ZONES.RESPAWN_TICKS, zone.respawnTicks)
            .set(NPC_ZONES.ACTIVE, zone.active)
            .set(NPC_ZONES.CREATED_BY, zone.createdBy)
            .set(NPC_ZONES.CREATED_AT_TICK, zone.createdAtTick)
            .execute()
    }

    @Transactional
    override fun update(zone: NpcZone): NpcZone? {
        val updated = dsl.update(NPC_ZONES)
            .set(NPC_ZONES.WEIGHTS, encodeWeights(zone.weights))
            .set(NPC_ZONES.MAX_CONCURRENT, zone.maxConcurrent)
            .set(NPC_ZONES.RESPAWN_TICKS, zone.respawnTicks)
            .set(NPC_ZONES.ACTIVE, zone.active)
            .where(NPC_ZONES.ZONE_ID.eq(zone.zoneId))
            .execute()
        return if (updated > 0) zone else null
    }

    @Transactional
    override fun delete(zoneId: UUID): Boolean =
        dsl.deleteFrom(NPC_ZONES).where(NPC_ZONES.ZONE_ID.eq(zoneId)).execute() > 0

    private fun encodeWeights(weights: Map<NpcType, Int>): JSON =
        JSON.valueOf(mapper.writeValueAsString(weights.mapKeys { it.key.value }))

    private fun decodeWeights(json: JSON): Map<NpcType, Int> =
        mapper.readValue(json.data(), WEIGHT_MAP_TYPE).mapKeys { NpcType(it.key) }

    private fun toDomain(record: NpcZonesRecord): NpcZone = NpcZone(
        zoneId = record.zoneId,
        worldId = WorldId(record.worldId),
        scope = NpcZoneScope.valueOf(record.scope),
        regionId = record.regionId?.let(::RegionId),
        nodeId = record.nodeId?.let(::NodeId),
        weights = decodeWeights(record.weights),
        maxConcurrent = record.maxConcurrent,
        respawnTicks = record.respawnTicks,
        active = record.active!!,
        createdBy = record.createdBy,
        createdAtTick = record.createdAtTick,
    )

    private companion object {
        private val WEIGHT_MAP_TYPE = object : TypeReference<Map<String, Int>>() {}
    }
}
