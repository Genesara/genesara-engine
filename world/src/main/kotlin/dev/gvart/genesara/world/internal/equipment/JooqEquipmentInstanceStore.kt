package dev.gvart.genesara.world.internal.equipment

import dev.gvart.genesara.player.AgentId
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
            .execute()
    }

    @Transactional(readOnly = true)
    override fun listByAgent(agentId: AgentId): List<EquipmentInstance> =
        dsl.selectFrom(AGENT_EQUIPMENT_INSTANCES)
            .where(AGENT_EQUIPMENT_INSTANCES.AGENT_ID.eq(agentId.id))
            .orderBy(AGENT_EQUIPMENT_INSTANCES.INSTANCE_ID.asc())
            .fetch(::toDomain)

    @Transactional
    override fun decrementDurability(instanceId: UUID, amount: Int): EquipmentInstance? {
        require(amount >= 0) { "decrement amount must be non-negative, got $amount" }
        // Single-round-trip UPDATE ... RETURNING. The earlier UPDATE-then-SELECT shape
        // had a small race: a concurrent delete between the two statements left the
        // SELECT returning null after a successful decrement, indistinguishable from
        // "no such row". RETURNING removes that gap and halves the round-trips.
        // GREATEST(durability_current - amount, 0) clamps at zero server-side; the
        // CHECK (durability_current >= 0) on the table is a backstop, not the gate.
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
    )
}
