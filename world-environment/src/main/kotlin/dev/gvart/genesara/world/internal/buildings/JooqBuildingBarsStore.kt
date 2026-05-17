package dev.gvart.genesara.world.internal.buildings

import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.world.BuildingBar
import dev.gvart.genesara.world.BuildingBarsStore
import dev.gvart.genesara.world.internal.jooq.tables.references.NODE_BUILDING_BARS
import org.jooq.DSLContext
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Component
internal class JooqBuildingBarsStore(
    private val dsl: DSLContext,
) : BuildingBarsStore {

    @Transactional
    override fun insertAll(bars: List<BuildingBar>) {
        if (bars.isEmpty()) return
        val insert = dsl.insertInto(
            NODE_BUILDING_BARS,
            NODE_BUILDING_BARS.INSTANCE_ID,
            NODE_BUILDING_BARS.SKILL_ID,
            NODE_BUILDING_BARS.PROGRESS_STEPS,
            NODE_BUILDING_BARS.TOTAL_STEPS,
        )
        bars.fold(insert) { acc, bar ->
            acc.values(bar.instanceId, bar.skill.value, bar.progressSteps, bar.totalSteps)
        }.execute()
    }

    @Transactional(readOnly = true)
    override fun barsByInstance(instanceId: UUID): List<BuildingBar> =
        dsl.selectFrom(NODE_BUILDING_BARS)
            .where(NODE_BUILDING_BARS.INSTANCE_ID.eq(instanceId))
            .orderBy(NODE_BUILDING_BARS.SKILL_ID.asc())
            .fetch(::toDomain)

    @Transactional(readOnly = true)
    override fun barsByInstances(instanceIds: Set<UUID>): Map<UUID, List<BuildingBar>> {
        if (instanceIds.isEmpty()) return emptyMap()
        return dsl.selectFrom(NODE_BUILDING_BARS)
            .where(NODE_BUILDING_BARS.INSTANCE_ID.`in`(instanceIds))
            .orderBy(NODE_BUILDING_BARS.INSTANCE_ID.asc(), NODE_BUILDING_BARS.SKILL_ID.asc())
            .fetch(::toDomain)
            .groupBy { it.instanceId }
    }

    /**
     * Increments `progress_steps` by 1 atomically. The `WHERE progress_steps < total_steps`
     * guard ensures the UPDATE only fires on rows that have room for another step —
     * an already-filled bar matches no row and returns null, letting the reducer raise
     * BarAlreadyComplete instead of tripping the schema CHECK (`progress_steps <= total_steps`)
     * on `total_steps + 1`.
     */
    @Transactional
    override fun advanceBar(instanceId: UUID, skill: SkillId): BuildingBar? =
        dsl.update(NODE_BUILDING_BARS)
            .set(NODE_BUILDING_BARS.PROGRESS_STEPS, NODE_BUILDING_BARS.PROGRESS_STEPS.plus(1))
            .where(NODE_BUILDING_BARS.INSTANCE_ID.eq(instanceId))
            .and(NODE_BUILDING_BARS.SKILL_ID.eq(skill.value))
            .and(NODE_BUILDING_BARS.PROGRESS_STEPS.lt(NODE_BUILDING_BARS.TOTAL_STEPS))
            .returningResult(NODE_BUILDING_BARS.asterisk())
            .fetchOne()
            ?.into(NODE_BUILDING_BARS)
            ?.let(::toDomain)

    private fun toDomain(
        record: dev.gvart.genesara.world.internal.jooq.tables.records.NodeBuildingBarsRecord,
    ): BuildingBar = BuildingBar(
        instanceId = record.instanceId,
        skill = SkillId(record.skillId),
        progressSteps = record.progressSteps ?: 0,
        totalSteps = record.totalSteps,
    )
}
