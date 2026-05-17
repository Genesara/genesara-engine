package dev.gvart.genesara.world.economy.internal.cultivation

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.AgentPlot
import dev.gvart.genesara.world.AgentPlotsStore
import dev.gvart.genesara.world.CropId
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.PlantedCrop
import dev.gvart.genesara.world.internal.jooq.tables.references.AGENT_PLOTS
import org.jooq.DSLContext
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Component
class JooqAgentPlotsStore(
    private val dsl: DSLContext,
) : AgentPlotsStore {

    @Transactional
    override fun insertEmpty(plot: AgentPlot) {
        require(plot.plant == null) { "insertEmpty called with a planted plot: $plot" }
        dsl.insertInto(AGENT_PLOTS)
            .set(AGENT_PLOTS.PLOT_ID, plot.plotId)
            .set(AGENT_PLOTS.BUILDING_INSTANCE_ID, plot.buildingInstanceId)
            .set(AGENT_PLOTS.NODE_ID, plot.nodeId.value)
            .execute()
    }

    @Transactional(readOnly = true)
    override fun findById(plotId: UUID): AgentPlot? =
        dsl.selectFrom(AGENT_PLOTS)
            .where(AGENT_PLOTS.PLOT_ID.eq(plotId))
            .fetchOne(::toDomain)

    @Transactional(readOnly = true)
    override fun findByBuilding(buildingInstanceId: UUID): AgentPlot? =
        dsl.selectFrom(AGENT_PLOTS)
            .where(AGENT_PLOTS.BUILDING_INSTANCE_ID.eq(buildingInstanceId))
            .fetchOne(::toDomain)

    @Transactional(readOnly = true)
    override fun listByNodes(nodes: Set<NodeId>): Map<NodeId, List<AgentPlot>> {
        if (nodes.isEmpty()) return emptyMap()
        return dsl.selectFrom(AGENT_PLOTS)
            .where(AGENT_PLOTS.NODE_ID.`in`(nodes.map { it.value }))
            .orderBy(AGENT_PLOTS.NODE_ID.asc(), AGENT_PLOTS.PLOT_ID.asc())
            .fetch(::toDomain)
            .groupBy { it.nodeId }
    }

    // UPDATE … RETURNING (not UPDATE + SELECT) closes the race against a concurrent
    // clear — same defense as `JooqBuildingsStore.advanceProgress`. Applies to the
    // three mutators below.
    @Transactional
    override fun plant(plotId: UUID, crop: PlantedCrop): AgentPlot? =
        dsl.update(AGENT_PLOTS)
            .set(AGENT_PLOTS.PLANTED_CROP, crop.cropId.value)
            .set(AGENT_PLOTS.PLANTED_AT_TICK, crop.plantedAtTick)
            .set(AGENT_PLOTS.LAST_TENDED_AT_TICK, crop.lastTendedAtTick)
            .set(AGENT_PLOTS.PLANTED_BY_AGENT_ID, crop.plantedByAgentId.id)
            .where(AGENT_PLOTS.PLOT_ID.eq(plotId))
            .and(AGENT_PLOTS.PLANTED_CROP.isNull)
            .returningResult(AGENT_PLOTS.asterisk())
            .fetchOne()
            ?.into(AGENT_PLOTS)
            ?.let(::toDomain)

    @Transactional
    override fun tend(plotId: UUID, tick: Long): AgentPlot? =
        dsl.update(AGENT_PLOTS)
            .set(AGENT_PLOTS.LAST_TENDED_AT_TICK, tick)
            .where(AGENT_PLOTS.PLOT_ID.eq(plotId))
            .and(AGENT_PLOTS.PLANTED_CROP.isNotNull)
            .returningResult(AGENT_PLOTS.asterisk())
            .fetchOne()
            ?.into(AGENT_PLOTS)
            ?.let(::toDomain)

    @Transactional
    override fun clearPlanting(plotId: UUID): AgentPlot? =
        dsl.update(AGENT_PLOTS)
            .setNull(AGENT_PLOTS.PLANTED_CROP)
            .setNull(AGENT_PLOTS.PLANTED_AT_TICK)
            .setNull(AGENT_PLOTS.LAST_TENDED_AT_TICK)
            .setNull(AGENT_PLOTS.PLANTED_BY_AGENT_ID)
            .where(AGENT_PLOTS.PLOT_ID.eq(plotId))
            .and(AGENT_PLOTS.PLANTED_CROP.isNotNull)
            .returningResult(AGENT_PLOTS.asterisk())
            .fetchOne()
            ?.into(AGENT_PLOTS)
            ?.let(::toDomain)

    @Transactional(readOnly = true)
    override fun listPlantedSnapshot(): List<AgentPlot> =
        dsl.selectFrom(AGENT_PLOTS)
            .where(AGENT_PLOTS.PLANTED_CROP.isNotNull)
            .orderBy(AGENT_PLOTS.PLOT_ID.asc())
            .fetch(::toDomain)

    private fun toDomain(
        record: dev.gvart.genesara.world.internal.jooq.tables.records.AgentPlotsRecord,
    ): AgentPlot {
        val cropName = record.plantedCrop
        val plantedAt = record.plantedAtTick
        val tendedAt = record.lastTendedAtTick
        val plantedBy = record.plantedByAgentId
        val plant = if (cropName != null && plantedAt != null && tendedAt != null && plantedBy != null) {
            PlantedCrop(
                cropId = CropId(cropName),
                plantedAtTick = plantedAt,
                lastTendedAtTick = tendedAt,
                plantedByAgentId = AgentId(plantedBy),
            )
        } else {
            null
        }
        return AgentPlot(
            plotId = record.plotId,
            buildingInstanceId = record.buildingInstanceId,
            nodeId = NodeId(record.nodeId),
            plant = plant,
        )
    }
}
