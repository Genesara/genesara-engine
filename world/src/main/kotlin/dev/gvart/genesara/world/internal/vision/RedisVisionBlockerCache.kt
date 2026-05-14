package dev.gvart.genesara.world.internal.vision

import dev.gvart.genesara.world.BuildingStatus
import dev.gvart.genesara.world.BuildingType
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.internal.buildings.BuildingsCatalog
import dev.gvart.genesara.world.internal.jooq.tables.references.NODE_BUILDINGS
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.util.UUID

@Component
internal class RedisVisionBlockerCache(
    private val redis: StringRedisTemplate,
    private val dsl: DSLContext,
    private val catalog: BuildingsCatalog,
    private val gateStates: dev.gvart.genesara.world.BuildingGateStateStore,
) : VisionBlockerCache {

    private val log = LoggerFactory.getLogger(javaClass)
    private val hash get() = redis.opsForHash<String, String>()

    override fun blockerHeights(nodes: Set<NodeId>): Map<NodeId, Int> {
        if (nodes.isEmpty()) return emptyMap()
        val orderedFields = nodes.toList()
        val raw = hash.multiGet(HASH_KEY, orderedFields.map { it.value.toString() })
        val out = HashMap<NodeId, Int>(orderedFields.size)
        for ((idx, value) in raw.withIndex()) {
            if (value == null) continue
            val parsed = value.toIntOrNull()
            if (parsed == null) {
                log.warn("vision-blocker hash field for node={} held malformed value '{}'", orderedFields[idx].value, value)
                continue
            }
            if (parsed > 0) out[orderedFields[idx]] = parsed
        }
        return out
    }

    override fun recomputeForNode(nodeId: NodeId) {
        // Surface Redis failures to the caller — the documented self-healing path
        // ("next mutation re-derives this node") only holds if the next mutation
        // actually happens; for a gate that someone left toggled this might never
        // come. Let the reducer's @Transactional roll back rather than ship a
        // silently-inconsistent cache.
        val total = sumBlockersAt(nodeId)
        if (total > 0) {
            hash.put(HASH_KEY, nodeId.value.toString(), total.toString())
        } else {
            hash.delete(HASH_KEY, nodeId.value.toString())
        }
    }

    override fun seedAll() {
        val typesWithBlocker = catalog.allDefs()
            .filter { it.sightBlockerHeight > 0 }
            .map { it.type.name }
            .toSet()

        val totals: Map<Long, Int> = if (typesWithBlocker.isEmpty()) {
            emptyMap()
        } else {
            val rows = dsl.select(NODE_BUILDINGS.NODE_ID, NODE_BUILDINGS.BUILDING_TYPE, NODE_BUILDINGS.INSTANCE_ID)
                .from(NODE_BUILDINGS)
                .where(NODE_BUILDINGS.STATUS.eq(BuildingStatus.ACTIVE.name))
                .and(NODE_BUILDINGS.BUILDING_TYPE.`in`(typesWithBlocker))
                .fetch()
            val accumulator = HashMap<Long, Int>()
            for (row in rows) {
                val type = BuildingType.valueOf(row[NODE_BUILDINGS.BUILDING_TYPE]!!)
                val instanceId = row[NODE_BUILDINGS.INSTANCE_ID]!!
                val nodeId = row[NODE_BUILDINGS.NODE_ID]!!
                val contribution = contributionFor(type, instanceId)
                if (contribution == 0) continue
                accumulator.merge(nodeId, contribution, Int::plus)
            }
            accumulator
        }

        // Build the replacement in a temp key, then atomically RENAME over the live
        // hash. A naive DELETE+PUTALL leaves a brief window where every blocker tile
        // reads as 0 and agents see through walls. RENAME flips the visible mapping
        // in a single Redis operation.
        if (totals.isEmpty()) {
            redis.delete(HASH_KEY)
            return
        }
        val staging = "$HASH_KEY:staging"
        redis.delete(staging)
        hash.putAll(staging, totals.entries.associate { (k, v) -> k.toString() to v.toString() })
        redis.rename(staging, HASH_KEY)
    }

    override fun flush() {
        redis.delete(HASH_KEY)
    }

    private fun sumBlockersAt(nodeId: NodeId): Int {
        val rows = dsl.select(NODE_BUILDINGS.BUILDING_TYPE, NODE_BUILDINGS.INSTANCE_ID)
            .from(NODE_BUILDINGS)
            .where(NODE_BUILDINGS.NODE_ID.eq(nodeId.value))
            .and(NODE_BUILDINGS.STATUS.eq(BuildingStatus.ACTIVE.name))
            .fetch()
        var total = 0
        for (row in rows) {
            val type = BuildingType.valueOf(row[NODE_BUILDINGS.BUILDING_TYPE]!!)
            val instanceId = row[NODE_BUILDINGS.INSTANCE_ID]!!
            total += contributionFor(type, instanceId)
        }
        return total
    }

    private fun contributionFor(type: BuildingType, instanceId: UUID): Int {
        val baseContribution = catalog.def(type).sightBlockerHeight
        if (baseContribution == 0) return 0
        return when (type) {
            // GATE blocks only while CLOSED; an OPEN gate is transparent.
            BuildingType.GATE -> if (gateStates.isOpen(instanceId) == false) baseContribution else 0
            else -> baseContribution
        }
    }

    private companion object {
        const val HASH_KEY = "world:vision:blockers"
    }
}
