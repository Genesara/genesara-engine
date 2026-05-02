package dev.gvart.genesara.world.internal.buildings

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.Building
import dev.gvart.genesara.world.BuildingStatus
import dev.gvart.genesara.world.BuildingType
import dev.gvart.genesara.world.BuildingsStore
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.internal.jooq.tables.references.NODE_BUILDINGS
import org.jooq.DSLContext
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Component
internal class JooqBuildingsStore(
    private val dsl: DSLContext,
) : BuildingsStore {

    @Transactional
    override fun insert(building: Building) {
        dsl.insertInto(NODE_BUILDINGS)
            .set(NODE_BUILDINGS.INSTANCE_ID, building.instanceId)
            .set(NODE_BUILDINGS.NODE_ID, building.nodeId.value)
            .set(NODE_BUILDINGS.BUILDING_TYPE, building.type.name)
            .set(NODE_BUILDINGS.STATUS, building.status.name)
            .set(NODE_BUILDINGS.BUILT_BY_AGENT_ID, building.builtByAgentId.id)
            .set(NODE_BUILDINGS.BUILT_AT_TICK, building.builtAtTick)
            .set(NODE_BUILDINGS.LAST_PROGRESS_TICK, building.lastProgressTick)
            .set(NODE_BUILDINGS.PROGRESS_STEPS, building.progressSteps)
            .set(NODE_BUILDINGS.TOTAL_STEPS, building.totalSteps)
            .set(NODE_BUILDINGS.HP_CURRENT, building.hpCurrent)
            .set(NODE_BUILDINGS.HP_MAX, building.hpMax)
            .execute()
    }

    @Transactional(readOnly = true)
    override fun findById(id: UUID): Building? =
        dsl.selectFrom(NODE_BUILDINGS)
            .where(NODE_BUILDINGS.INSTANCE_ID.eq(id))
            .fetchOne(::toDomain)

    @Transactional(readOnly = true)
    override fun findInProgress(node: NodeId, agent: AgentId, type: BuildingType): Building? =
        dsl.selectFrom(NODE_BUILDINGS)
            .where(NODE_BUILDINGS.NODE_ID.eq(node.value))
            .and(NODE_BUILDINGS.BUILT_BY_AGENT_ID.eq(agent.id))
            .and(NODE_BUILDINGS.BUILDING_TYPE.eq(type.name))
            .and(NODE_BUILDINGS.STATUS.eq(BuildingStatus.UNDER_CONSTRUCTION.name))
            .fetchOne(::toDomain)

    @Transactional(readOnly = true)
    override fun listAtNode(node: NodeId): List<Building> =
        dsl.selectFrom(NODE_BUILDINGS)
            .where(NODE_BUILDINGS.NODE_ID.eq(node.value))
            .orderBy(NODE_BUILDINGS.INSTANCE_ID.asc())
            .fetch(::toDomain)

    @Transactional(readOnly = true)
    override fun listByNodes(nodes: Set<NodeId>): Map<NodeId, List<Building>> {
        if (nodes.isEmpty()) return emptyMap()
        return dsl.selectFrom(NODE_BUILDINGS)
            .where(NODE_BUILDINGS.NODE_ID.`in`(nodes.map { it.value }))
            .orderBy(NODE_BUILDINGS.NODE_ID.asc(), NODE_BUILDINGS.INSTANCE_ID.asc())
            .fetch(::toDomain)
            .groupBy { it.nodeId }
    }

    /**
     * `UPDATE ... RETURNING` rather than `UPDATE` + `SELECT` to close a race
     * window: a concurrent delete between the two statements would leave the
     * SELECT empty after a successful update, indistinguishable from "no such
     * row." Same defense as `JooqEquipmentInstanceStore.decrementDurability`.
     */
    @Transactional
    override fun advanceProgress(id: UUID, newProgress: Int, asOfTick: Long): Building? =
        dsl.update(NODE_BUILDINGS)
            .set(NODE_BUILDINGS.PROGRESS_STEPS, newProgress)
            .set(NODE_BUILDINGS.LAST_PROGRESS_TICK, asOfTick)
            .where(NODE_BUILDINGS.INSTANCE_ID.eq(id))
            .and(NODE_BUILDINGS.STATUS.eq(BuildingStatus.UNDER_CONSTRUCTION.name))
            .returningResult(NODE_BUILDINGS.asterisk())
            .fetchOne()
            ?.into(NODE_BUILDINGS)
            ?.let(::toDomain)

    @Transactional
    override fun complete(id: UUID, asOfTick: Long): Building? =
        dsl.update(NODE_BUILDINGS)
            .set(NODE_BUILDINGS.STATUS, BuildingStatus.ACTIVE.name)
            .set(NODE_BUILDINGS.PROGRESS_STEPS, NODE_BUILDINGS.TOTAL_STEPS)
            .set(NODE_BUILDINGS.LAST_PROGRESS_TICK, asOfTick)
            .where(NODE_BUILDINGS.INSTANCE_ID.eq(id))
            .and(NODE_BUILDINGS.STATUS.eq(BuildingStatus.UNDER_CONSTRUCTION.name))
            .returningResult(NODE_BUILDINGS.asterisk())
            .fetchOne()
            ?.into(NODE_BUILDINGS)
            ?.let(::toDomain)

    private fun toDomain(
        record: dev.gvart.genesara.world.internal.jooq.tables.records.NodeBuildingsRecord,
    ): Building = Building(
        instanceId = record.instanceId,
        nodeId = NodeId(record.nodeId),
        type = BuildingType.valueOf(record.buildingType),
        status = BuildingStatus.valueOf(record.status),
        builtByAgentId = AgentId(record.builtByAgentId),
        builtAtTick = record.builtAtTick,
        lastProgressTick = record.lastProgressTick,
        progressSteps = record.progressSteps,
        totalSteps = record.totalSteps,
        hpCurrent = record.hpCurrent,
        hpMax = record.hpMax,
    )
}
