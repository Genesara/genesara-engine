package dev.gvart.genesara.world.environment.internal.npc

import dev.gvart.genesara.world.NodeClearedTimestampStore
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.Npc
import dev.gvart.genesara.world.NpcId
import dev.gvart.genesara.world.NpcType
import dev.gvart.genesara.world.NpcsStore
import dev.gvart.genesara.world.internal.jooq.tables.references.NODES
import dev.gvart.genesara.world.internal.jooq.tables.references.NPCS
import org.jooq.DSLContext
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class JooqNpcsStore(
    private val dsl: DSLContext,
) : NpcsStore {

    @Transactional
    override fun insert(npc: Npc) {
        dsl.insertInto(NPCS)
            .set(NPCS.NPC_ID, npc.id.value)
            .set(NPCS.NPC_TYPE, npc.type.value)
            .set(NPCS.NODE_ID, npc.nodeId.value)
            .set(NPCS.SPAWN_NODE_ID, npc.spawnNodeId.value)
            .set(NPCS.HP_MAX, npc.hpMax)
            .set(NPCS.HP_CURRENT, npc.hpCurrent)
            .set(NPCS.SPAWNED_AT_TICK, npc.spawnedAtTick)
            .set(NPCS.LAST_ATTACK_TICK, npc.lastAttackTick)
            .execute()
    }

    @Transactional(readOnly = true)
    override fun findById(npcId: NpcId): Npc? =
        dsl.selectFrom(NPCS)
            .where(NPCS.NPC_ID.eq(npcId.value))
            .fetchOne(::toDomain)

    @Transactional(readOnly = true)
    override fun byNodes(nodeIds: Collection<NodeId>): List<Npc> {
        if (nodeIds.isEmpty()) return emptyList()
        return dsl.selectFrom(NPCS)
            .where(NPCS.NODE_ID.`in`(nodeIds.map { it.value }))
            .fetch(::toDomain)
    }

    @Transactional
    override fun delete(npcId: NpcId): Boolean =
        dsl.deleteFrom(NPCS)
            .where(NPCS.NPC_ID.eq(npcId.value))
            .execute() > 0

    @Transactional(readOnly = true)
    override fun countAtNode(nodeId: NodeId): Int =
        dsl.fetchCount(NPCS, NPCS.NODE_ID.eq(nodeId.value))

    @Transactional
    override fun update(npc: Npc) {
        dsl.update(NPCS)
            .set(NPCS.NODE_ID, npc.nodeId.value)
            .set(NPCS.HP_CURRENT, npc.hpCurrent)
            .set(NPCS.LAST_ATTACK_TICK, npc.lastAttackTick)
            .where(NPCS.NPC_ID.eq(npc.id.value))
            .execute()
    }

    private fun toDomain(record: dev.gvart.genesara.world.internal.jooq.tables.records.NpcsRecord): Npc =
        Npc(
            id = NpcId(record.npcId),
            type = NpcType(record.npcType),
            nodeId = NodeId(record.nodeId),
            spawnNodeId = NodeId(record.spawnNodeId),
            hpCurrent = record.hpCurrent,
            hpMax = record.hpMax,
            spawnedAtTick = record.spawnedAtTick,
            lastAttackTick = record.lastAttackTick ?: 0L,
        )
}

@Component
class JooqNodeClearedTimestampStore(
    private val dsl: DSLContext,
) : NodeClearedTimestampStore {

    @Transactional(readOnly = true)
    override fun lastClearedTick(nodeId: NodeId): Long =
        dsl.select(NODES.LAST_CLEARED_TICK)
            .from(NODES)
            .where(NODES.ID.eq(nodeId.value))
            .fetchOne()?.value1() ?: 0L

    @Transactional
    override fun setLastClearedTick(nodeId: NodeId, tick: Long) {
        dsl.update(NODES)
            .set(NODES.LAST_CLEARED_TICK, tick)
            .where(NODES.ID.eq(nodeId.value))
            .execute()
    }
}
