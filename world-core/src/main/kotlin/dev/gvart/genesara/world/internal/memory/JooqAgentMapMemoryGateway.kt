package dev.gvart.genesara.world.internal.memory

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.AgentMapMemoryGateway
import dev.gvart.genesara.world.Biome
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.NodeMemoryUpdate
import dev.gvart.genesara.world.RecalledNode
import dev.gvart.genesara.world.RegionId
import dev.gvart.genesara.world.Terrain
import dev.gvart.genesara.world.internal.jooq.tables.references.AGENT_NODE_MEMORY
import dev.gvart.genesara.world.internal.jooq.tables.references.NODES
import org.jooq.DSLContext
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
internal class JooqAgentMapMemoryGateway(
    private val dsl: DSLContext,
) : AgentMapMemoryGateway {

    /**
     * Per-row upsert. Typical batch is small (sight radius 1 → ~7 nodes, radius 2 →
     * ~19); a multi-row VALUES form would be a worthwhile optimization only if larger
     * sights ship. Writes are agent-scoped (PK includes `agent_id`) and the conflict
     * branch is idempotent under last-writer-wins on `last_seen_tick` /
     * `last_terrain` / `last_biome`, safe under READ COMMITTED.
     */
    @Transactional
    override fun recordVisible(agentId: AgentId, updates: Collection<NodeMemoryUpdate>, tick: Long) {
        if (updates.isEmpty()) return
        for (update in updates) {
            dsl.insertInto(AGENT_NODE_MEMORY)
                .set(AGENT_NODE_MEMORY.AGENT_ID, agentId.id)
                .set(AGENT_NODE_MEMORY.NODE_ID, update.nodeId.value)
                .set(AGENT_NODE_MEMORY.FIRST_SEEN_TICK, tick)
                .set(AGENT_NODE_MEMORY.LAST_SEEN_TICK, tick)
                .set(AGENT_NODE_MEMORY.LAST_TERRAIN, update.terrain.name)
                .set(AGENT_NODE_MEMORY.LAST_BIOME, update.biome?.name)
                .onConflict(AGENT_NODE_MEMORY.AGENT_ID, AGENT_NODE_MEMORY.NODE_ID)
                .doUpdate()
                // first_seen_tick is intentionally not in the doUpdate set — it locks on insert.
                .set(AGENT_NODE_MEMORY.LAST_SEEN_TICK, tick)
                .set(AGENT_NODE_MEMORY.LAST_TERRAIN, update.terrain.name)
                .set(AGENT_NODE_MEMORY.LAST_BIOME, update.biome?.name)
                .execute()
        }
    }

    @Transactional(readOnly = true)
    override fun recall(agentId: AgentId): List<RecalledNode> =
        dsl.select(
            AGENT_NODE_MEMORY.NODE_ID,
            AGENT_NODE_MEMORY.FIRST_SEEN_TICK,
            AGENT_NODE_MEMORY.LAST_SEEN_TICK,
            AGENT_NODE_MEMORY.LAST_TERRAIN,
            AGENT_NODE_MEMORY.LAST_BIOME,
            NODES.REGION_ID,
            NODES.Q,
            NODES.R,
        )
            .from(AGENT_NODE_MEMORY)
            .join(NODES).on(NODES.ID.eq(AGENT_NODE_MEMORY.NODE_ID))
            .where(AGENT_NODE_MEMORY.AGENT_ID.eq(agentId.id))
            .orderBy(AGENT_NODE_MEMORY.NODE_ID.asc())
            .fetch {
                RecalledNode(
                    nodeId = NodeId(it[AGENT_NODE_MEMORY.NODE_ID]!!),
                    regionId = RegionId(it[NODES.REGION_ID]!!),
                    q = it[NODES.Q]!!,
                    r = it[NODES.R]!!,
                    terrain = Terrain.valueOf(it[AGENT_NODE_MEMORY.LAST_TERRAIN]!!),
                    biome = it[AGENT_NODE_MEMORY.LAST_BIOME]?.let(Biome::valueOf),
                    firstSeenTick = it[AGENT_NODE_MEMORY.FIRST_SEEN_TICK]!!,
                    lastSeenTick = it[AGENT_NODE_MEMORY.LAST_SEEN_TICK]!!,
                )
            }
}
