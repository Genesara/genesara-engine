package dev.gvart.genesara.world.internal.buildings

import dev.gvart.genesara.world.BuildingGateStateStore
import dev.gvart.genesara.world.internal.jooq.tables.references.BUILDING_GATE_STATES
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Component
internal class JooqBuildingGateStateStore(
    private val dsl: DSLContext,
) : BuildingGateStateStore {

    @Transactional
    override fun insertClosed(gateInstanceId: UUID) {
        dsl.insertInto(BUILDING_GATE_STATES)
            .set(BUILDING_GATE_STATES.BUILDING_INSTANCE_ID, gateInstanceId)
            .set(BUILDING_GATE_STATES.IS_OPEN, false)
            .execute()
    }

    @Transactional(readOnly = true)
    override fun isOpen(gateInstanceId: UUID): Boolean? =
        dsl.select(BUILDING_GATE_STATES.IS_OPEN)
            .from(BUILDING_GATE_STATES)
            .where(BUILDING_GATE_STATES.BUILDING_INSTANCE_ID.eq(gateInstanceId))
            .fetchOne(BUILDING_GATE_STATES.IS_OPEN)

    @Transactional
    override fun toggle(gateInstanceId: UUID): Boolean? {
        val flipped = DSL.case_()
            .`when`(BUILDING_GATE_STATES.IS_OPEN.eq(true), DSL.value(false))
            .otherwise(DSL.value(true))
        return dsl.update(BUILDING_GATE_STATES)
            .set(BUILDING_GATE_STATES.IS_OPEN, flipped)
            .where(BUILDING_GATE_STATES.BUILDING_INSTANCE_ID.eq(gateInstanceId))
            .returningResult(BUILDING_GATE_STATES.IS_OPEN)
            .fetchOne(BUILDING_GATE_STATES.IS_OPEN)
    }
}
