package dev.gvart.genesara.world.internal.equipment

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.EquipSlot
import dev.gvart.genesara.world.EquipmentInstance
import dev.gvart.genesara.world.EquipmentInstanceStore
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.Rarity
import dev.gvart.genesara.world.internal.jooq.tables.references.AGENT_EQUIPMENT_INSTANCES
import org.jooq.DSLContext
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Component
internal class JooqEquipmentInstanceStore(
    private val dsl: DSLContext,
) : EquipmentInstanceStore {

    @Transactional
    override fun insert(instance: EquipmentInstance) {
        dsl.insertInto(AGENT_EQUIPMENT_INSTANCES)
            .set(AGENT_EQUIPMENT_INSTANCES.INSTANCE_ID, instance.instanceId)
            .set(AGENT_EQUIPMENT_INSTANCES.AGENT_ID, instance.agentId.id)
            .set(AGENT_EQUIPMENT_INSTANCES.ITEM_ID, instance.itemId.value)
            .set(AGENT_EQUIPMENT_INSTANCES.RARITY, instance.rarity.name)
            .set(AGENT_EQUIPMENT_INSTANCES.DURABILITY_CURRENT, instance.durabilityCurrent)
            .set(AGENT_EQUIPMENT_INSTANCES.DURABILITY_MAX, instance.durabilityMax)
            .set(AGENT_EQUIPMENT_INSTANCES.CREATOR_AGENT_ID, instance.creatorAgentId?.id)
            .set(AGENT_EQUIPMENT_INSTANCES.CREATED_AT_TICK, instance.createdAtTick)
            .set(AGENT_EQUIPMENT_INSTANCES.EQUIPPED_IN_SLOT, instance.equippedInSlot?.name)
            .execute()
    }

    @Transactional(readOnly = true)
    override fun findById(instanceId: UUID): EquipmentInstance? =
        dsl.selectFrom(AGENT_EQUIPMENT_INSTANCES)
            .where(AGENT_EQUIPMENT_INSTANCES.INSTANCE_ID.eq(instanceId))
            .fetchOne(::toDomain)

    @Transactional(readOnly = true)
    override fun listByAgent(agentId: AgentId): List<EquipmentInstance> =
        dsl.selectFrom(AGENT_EQUIPMENT_INSTANCES)
            .where(AGENT_EQUIPMENT_INSTANCES.AGENT_ID.eq(agentId.id))
            .orderBy(AGENT_EQUIPMENT_INSTANCES.INSTANCE_ID.asc())
            .fetch(::toDomain)

    @Transactional(readOnly = true)
    override fun equippedFor(agentId: AgentId): Map<EquipSlot, EquipmentInstance> =
        dsl.selectFrom(AGENT_EQUIPMENT_INSTANCES)
            .where(AGENT_EQUIPMENT_INSTANCES.AGENT_ID.eq(agentId.id))
            .and(AGENT_EQUIPMENT_INSTANCES.EQUIPPED_IN_SLOT.isNotNull)
            .fetch(::toDomain)
            .associateBy { it.equippedInSlot!! }

    @Transactional
    override fun assignToSlot(instanceId: UUID, agentId: AgentId, slot: EquipSlot): EquipmentInstance? =
        dsl.update(AGENT_EQUIPMENT_INSTANCES)
            .set(AGENT_EQUIPMENT_INSTANCES.EQUIPPED_IN_SLOT, slot.name)
            .where(AGENT_EQUIPMENT_INSTANCES.INSTANCE_ID.eq(instanceId))
            .and(AGENT_EQUIPMENT_INSTANCES.AGENT_ID.eq(agentId.id))
            .returningResult(AGENT_EQUIPMENT_INSTANCES.asterisk())
            .fetchOne()
            ?.into(AGENT_EQUIPMENT_INSTANCES)
            ?.let(::toDomain)

    @Transactional
    override fun clearSlot(agentId: AgentId, slot: EquipSlot): EquipmentInstance? =
        dsl.update(AGENT_EQUIPMENT_INSTANCES)
            .setNull(AGENT_EQUIPMENT_INSTANCES.EQUIPPED_IN_SLOT)
            .where(AGENT_EQUIPMENT_INSTANCES.AGENT_ID.eq(agentId.id))
            .and(AGENT_EQUIPMENT_INSTANCES.EQUIPPED_IN_SLOT.eq(slot.name))
            .returningResult(AGENT_EQUIPMENT_INSTANCES.asterisk())
            .fetchOne()
            ?.into(AGENT_EQUIPMENT_INSTANCES)
            ?.let(::toDomain)

    /**
     * `UPDATE ... RETURNING` rather than `UPDATE` + `SELECT` to close a race window: a
     * concurrent delete between the two statements left the SELECT empty after a
     * successful decrement, indistinguishable from "no such row." `GREATEST(...,0)`
     * clamps server-side; the CHECK constraint is a backstop.
     */
    @Transactional
    override fun decrementDurability(instanceId: UUID, amount: Int): EquipmentInstance? {
        require(amount >= 0) { "decrement amount must be non-negative, got $amount" }
        val newCurrent = org.jooq.impl.DSL.greatest(
            AGENT_EQUIPMENT_INSTANCES.DURABILITY_CURRENT.minus(amount),
            org.jooq.impl.DSL.value(0),
        )
        return dsl.update(AGENT_EQUIPMENT_INSTANCES)
            .set(AGENT_EQUIPMENT_INSTANCES.DURABILITY_CURRENT, newCurrent)
            .where(AGENT_EQUIPMENT_INSTANCES.INSTANCE_ID.eq(instanceId))
            .returningResult(AGENT_EQUIPMENT_INSTANCES.asterisk())
            .fetchOne()
            ?.into(AGENT_EQUIPMENT_INSTANCES)
            ?.let(::toDomain)
    }

    @Transactional
    override fun delete(instanceId: UUID): Boolean =
        dsl.deleteFrom(AGENT_EQUIPMENT_INSTANCES)
            .where(AGENT_EQUIPMENT_INSTANCES.INSTANCE_ID.eq(instanceId))
            .execute() > 0

    private fun toDomain(
        record: dev.gvart.genesara.world.internal.jooq.tables.records.AgentEquipmentInstancesRecord,
    ): EquipmentInstance = EquipmentInstance(
        instanceId = record.instanceId,
        agentId = AgentId(record.agentId),
        itemId = ItemId(record.itemId),
        rarity = Rarity.valueOf(record.rarity),
        durabilityCurrent = record.durabilityCurrent,
        durabilityMax = record.durabilityMax,
        creatorAgentId = record.creatorAgentId?.let(::AgentId),
        createdAtTick = record.createdAtTick,
        equippedInSlot = record.equippedInSlot?.let(EquipSlot::valueOf),
    )
}
