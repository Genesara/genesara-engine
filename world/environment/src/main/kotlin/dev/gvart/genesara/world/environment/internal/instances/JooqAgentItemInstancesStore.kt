package dev.gvart.genesara.world.environment.internal.instances

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.AgentItemInstancesStore
import dev.gvart.genesara.world.EquipSlot
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.ItemInstance
import dev.gvart.genesara.world.MountId
import dev.gvart.genesara.world.MountSlot
import dev.gvart.genesara.world.Rarity
import dev.gvart.genesara.world.internal.jooq.tables.references.AGENT_ITEM_INSTANCES
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

private const val CATEGORY_EQUIPMENT = "EQUIPMENT"
private const val CATEGORY_KEY = "KEY"
private const val CATEGORY_MOUNT_GEAR = "MOUNT_GEAR"

@Component
internal class JooqAgentItemInstancesStore(
    private val dsl: DSLContext,
) : AgentItemInstancesStore {

    @Transactional
    override fun insert(instance: ItemInstance) {
        when (instance) {
            is ItemInstance.Equipment -> insertEquipment(instance)
            is ItemInstance.Key -> insertKey(instance)
            is ItemInstance.MountGear -> insertMountGear(instance)
        }
    }

    private fun insertEquipment(instance: ItemInstance.Equipment) {
        dsl.insertInto(AGENT_ITEM_INSTANCES)
            .set(AGENT_ITEM_INSTANCES.INSTANCE_ID, instance.instanceId)
            .set(AGENT_ITEM_INSTANCES.AGENT_ID, instance.agentId.id)
            .set(AGENT_ITEM_INSTANCES.ITEM_ID, instance.itemId.value)
            .set(AGENT_ITEM_INSTANCES.CATEGORY, CATEGORY_EQUIPMENT)
            .set(AGENT_ITEM_INSTANCES.CREATED_AT_TICK, instance.createdAtTick)
            .set(AGENT_ITEM_INSTANCES.RARITY, instance.rarity.name)
            .set(AGENT_ITEM_INSTANCES.DURABILITY_CURRENT, instance.durabilityCurrent)
            .set(AGENT_ITEM_INSTANCES.DURABILITY_MAX, instance.durabilityMax)
            .set(AGENT_ITEM_INSTANCES.CREATOR_AGENT_ID, instance.creatorAgentId?.id)
            .set(AGENT_ITEM_INSTANCES.EQUIPPED_IN_SLOT, instance.equippedInSlot?.name)
            .execute()
    }

    private fun insertKey(instance: ItemInstance.Key) {
        dsl.insertInto(AGENT_ITEM_INSTANCES)
            .set(AGENT_ITEM_INSTANCES.INSTANCE_ID, instance.instanceId)
            .set(AGENT_ITEM_INSTANCES.AGENT_ID, instance.agentId.id)
            .set(AGENT_ITEM_INSTANCES.ITEM_ID, instance.itemId.value)
            .set(AGENT_ITEM_INSTANCES.CATEGORY, CATEGORY_KEY)
            .set(AGENT_ITEM_INSTANCES.CREATED_AT_TICK, instance.createdAtTick)
            .set(AGENT_ITEM_INSTANCES.BOUND_BUILDING_ID, instance.gateInstanceId)
            .execute()
    }

    private fun insertMountGear(instance: ItemInstance.MountGear) {
        dsl.insertInto(AGENT_ITEM_INSTANCES)
            .set(AGENT_ITEM_INSTANCES.INSTANCE_ID, instance.instanceId)
            .set(AGENT_ITEM_INSTANCES.AGENT_ID, instance.agentId.id)
            .set(AGENT_ITEM_INSTANCES.ITEM_ID, instance.itemId.value)
            .set(AGENT_ITEM_INSTANCES.CATEGORY, CATEGORY_MOUNT_GEAR)
            .set(AGENT_ITEM_INSTANCES.CREATED_AT_TICK, instance.createdAtTick)
            .set(AGENT_ITEM_INSTANCES.RARITY, instance.rarity.name)
            .set(AGENT_ITEM_INSTANCES.DURABILITY_CURRENT, instance.durabilityCurrent)
            .set(AGENT_ITEM_INSTANCES.DURABILITY_MAX, instance.durabilityMax)
            .set(AGENT_ITEM_INSTANCES.CREATOR_AGENT_ID, instance.creatorAgentId?.id)
            .set(AGENT_ITEM_INSTANCES.EQUIPPED_ON_MOUNT_ID, instance.equippedOnMount)
            .set(AGENT_ITEM_INSTANCES.EQUIPPED_MOUNT_SLOT, instance.equippedMountSlot?.name)
            .execute()
    }

    @Transactional(readOnly = true)
    override fun findById(instanceId: UUID): ItemInstance? =
        dsl.selectFrom(AGENT_ITEM_INSTANCES)
            .where(AGENT_ITEM_INSTANCES.INSTANCE_ID.eq(instanceId))
            .fetchOne(::toDomain)

    @Transactional(readOnly = true)
    override fun listByAgent(agentId: AgentId): List<ItemInstance> =
        dsl.selectFrom(AGENT_ITEM_INSTANCES)
            .where(AGENT_ITEM_INSTANCES.AGENT_ID.eq(agentId.id))
            .orderBy(AGENT_ITEM_INSTANCES.CREATED_AT_TICK.asc(), AGENT_ITEM_INSTANCES.INSTANCE_ID.asc())
            .fetch(::toDomain)

    @Transactional
    override fun delete(instanceId: UUID): Boolean =
        dsl.deleteFrom(AGENT_ITEM_INSTANCES)
            .where(AGENT_ITEM_INSTANCES.INSTANCE_ID.eq(instanceId))
            .execute() > 0

    @Transactional(readOnly = true)
    override fun equippedFor(agentId: AgentId): Map<EquipSlot, ItemInstance.Equipment> =
        dsl.selectFrom(AGENT_ITEM_INSTANCES)
            .where(AGENT_ITEM_INSTANCES.AGENT_ID.eq(agentId.id))
            .and(AGENT_ITEM_INSTANCES.CATEGORY.eq(CATEGORY_EQUIPMENT))
            .and(AGENT_ITEM_INSTANCES.EQUIPPED_IN_SLOT.isNotNull)
            .fetch(::toEquipment)
            .associateBy { it.equippedInSlot!! }

    @Transactional(readOnly = true)
    override fun equippedForAll(agents: Set<AgentId>): Map<AgentId, Map<EquipSlot, ItemInstance.Equipment>> {
        if (agents.isEmpty()) return emptyMap()
        return dsl.selectFrom(AGENT_ITEM_INSTANCES)
            .where(AGENT_ITEM_INSTANCES.AGENT_ID.`in`(agents.map { it.id }))
            .and(AGENT_ITEM_INSTANCES.CATEGORY.eq(CATEGORY_EQUIPMENT))
            .and(AGENT_ITEM_INSTANCES.EQUIPPED_IN_SLOT.isNotNull)
            .fetch(::toEquipment)
            .groupBy({ it.agentId }, { it })
            .mapValues { (_, instances) -> instances.associateBy { it.equippedInSlot!! } }
    }

    @Transactional
    override fun assignToSlot(instanceId: UUID, agentId: AgentId, slot: EquipSlot): ItemInstance.Equipment? =
        dsl.update(AGENT_ITEM_INSTANCES)
            .set(AGENT_ITEM_INSTANCES.EQUIPPED_IN_SLOT, slot.name)
            .where(AGENT_ITEM_INSTANCES.INSTANCE_ID.eq(instanceId))
            .and(AGENT_ITEM_INSTANCES.AGENT_ID.eq(agentId.id))
            .and(AGENT_ITEM_INSTANCES.CATEGORY.eq(CATEGORY_EQUIPMENT))
            .returningResult(AGENT_ITEM_INSTANCES.asterisk())
            .fetchOne()
            ?.into(AGENT_ITEM_INSTANCES)
            ?.let(::toEquipment)

    @Transactional
    override fun clearSlot(agentId: AgentId, slot: EquipSlot): ItemInstance.Equipment? =
        dsl.update(AGENT_ITEM_INSTANCES)
            .setNull(AGENT_ITEM_INSTANCES.EQUIPPED_IN_SLOT)
            .where(AGENT_ITEM_INSTANCES.AGENT_ID.eq(agentId.id))
            .and(AGENT_ITEM_INSTANCES.EQUIPPED_IN_SLOT.eq(slot.name))
            .returningResult(AGENT_ITEM_INSTANCES.asterisk())
            .fetchOne()
            ?.into(AGENT_ITEM_INSTANCES)
            ?.let(::toEquipment)

    /**
     * `UPDATE ... RETURNING` rather than `UPDATE` + `SELECT` to close a race window: a
     * concurrent delete between the two statements left the SELECT empty after a
     * successful decrement, indistinguishable from "no such row." `GREATEST(...,0)`
     * clamps server-side; the CHECK constraint is a backstop.
     */
    @Transactional
    override fun decrementDurability(instanceId: UUID, amount: Int): ItemInstance.Equipment? {
        require(amount >= 0) { "decrement amount must be non-negative, got $amount" }
        val newCurrent = DSL.greatest(
            AGENT_ITEM_INSTANCES.DURABILITY_CURRENT.minus(amount),
            DSL.value(0),
        )
        return dsl.update(AGENT_ITEM_INSTANCES)
            .set(AGENT_ITEM_INSTANCES.DURABILITY_CURRENT, newCurrent)
            .where(AGENT_ITEM_INSTANCES.INSTANCE_ID.eq(instanceId))
            .and(AGENT_ITEM_INSTANCES.CATEGORY.eq(CATEGORY_EQUIPMENT))
            .returningResult(AGENT_ITEM_INSTANCES.asterisk())
            .fetchOne()
            ?.into(AGENT_ITEM_INSTANCES)
            ?.let(::toEquipment)
    }

    @Transactional(readOnly = true)
    override fun agentHoldsKeyFor(agent: AgentId, gateInstanceId: UUID): Boolean =
        dsl.fetchExists(
            dsl.selectOne()
                .from(AGENT_ITEM_INSTANCES)
                .where(AGENT_ITEM_INSTANCES.AGENT_ID.eq(agent.id))
                .and(AGENT_ITEM_INSTANCES.CATEGORY.eq(CATEGORY_KEY))
                .and(AGENT_ITEM_INSTANCES.BOUND_BUILDING_ID.eq(gateInstanceId)),
        )

    @Transactional
    override fun stowOnMount(instanceId: UUID, agentId: AgentId, mountId: MountId): ItemInstance? =
        dsl.update(AGENT_ITEM_INSTANCES)
            .set(AGENT_ITEM_INSTANCES.STOWED_IN_MOUNT_ID, mountId.value)
            .where(AGENT_ITEM_INSTANCES.INSTANCE_ID.eq(instanceId))
            .and(AGENT_ITEM_INSTANCES.AGENT_ID.eq(agentId.id))
            .and(AGENT_ITEM_INSTANCES.EQUIPPED_IN_SLOT.isNull)
            .and(AGENT_ITEM_INSTANCES.EQUIPPED_ON_MOUNT_ID.isNull)
            .returningResult(AGENT_ITEM_INSTANCES.asterisk())
            .fetchOne()
            ?.into(AGENT_ITEM_INSTANCES)
            ?.let(::toDomain)

    @Transactional
    override fun unstowFromMount(instanceId: UUID): ItemInstance? =
        dsl.update(AGENT_ITEM_INSTANCES)
            .setNull(AGENT_ITEM_INSTANCES.STOWED_IN_MOUNT_ID)
            .where(AGENT_ITEM_INSTANCES.INSTANCE_ID.eq(instanceId))
            .returningResult(AGENT_ITEM_INSTANCES.asterisk())
            .fetchOne()
            ?.into(AGENT_ITEM_INSTANCES)
            ?.let(::toDomain)

    @Transactional(readOnly = true)
    override fun byStowedOnMount(mountId: MountId): List<ItemInstance> =
        dsl.selectFrom(AGENT_ITEM_INSTANCES)
            .where(AGENT_ITEM_INSTANCES.STOWED_IN_MOUNT_ID.eq(mountId.value))
            .fetch(::toDomain)

    @Transactional(readOnly = true)
    override fun gearOnMount(mountId: MountId, slot: MountSlot): ItemInstance.MountGear? =
        dsl.selectFrom(AGENT_ITEM_INSTANCES)
            .where(AGENT_ITEM_INSTANCES.EQUIPPED_ON_MOUNT_ID.eq(mountId.value))
            .and(AGENT_ITEM_INSTANCES.EQUIPPED_MOUNT_SLOT.eq(slot.name))
            .fetchOne(::toMountGear)

    private fun toDomain(
        record: dev.gvart.genesara.world.internal.jooq.tables.records.AgentItemInstancesRecord,
    ): ItemInstance = when (record.category) {
        CATEGORY_EQUIPMENT -> toEquipment(record)
        CATEGORY_KEY -> toKey(record)
        CATEGORY_MOUNT_GEAR -> toMountGear(record)
        else -> error("Unknown item-instance category '${record.category}' on row ${record.instanceId}")
    }

    private fun toEquipment(
        record: dev.gvart.genesara.world.internal.jooq.tables.records.AgentItemInstancesRecord,
    ): ItemInstance.Equipment = ItemInstance.Equipment(
        instanceId = record.instanceId,
        agentId = AgentId(record.agentId),
        itemId = ItemId(record.itemId),
        rarity = Rarity.valueOf(requireNotNull(record.rarity) { "EQUIPMENT row ${record.instanceId} missing rarity" }),
        durabilityCurrent = requireNotNull(record.durabilityCurrent) { "EQUIPMENT row ${record.instanceId} missing durability_current" },
        durabilityMax = requireNotNull(record.durabilityMax) { "EQUIPMENT row ${record.instanceId} missing durability_max" },
        creatorAgentId = record.creatorAgentId?.let(::AgentId),
        createdAtTick = record.createdAtTick,
        equippedInSlot = record.equippedInSlot?.let(EquipSlot::valueOf),
    )

    private fun toKey(
        record: dev.gvart.genesara.world.internal.jooq.tables.records.AgentItemInstancesRecord,
    ): ItemInstance.Key = ItemInstance.Key(
        instanceId = record.instanceId,
        agentId = AgentId(record.agentId),
        itemId = ItemId(record.itemId),
        gateInstanceId = requireNotNull(record.boundBuildingId) { "KEY row ${record.instanceId} missing bound_building_id" },
        createdAtTick = record.createdAtTick,
    )

    private fun toMountGear(
        record: dev.gvart.genesara.world.internal.jooq.tables.records.AgentItemInstancesRecord,
    ): ItemInstance.MountGear = ItemInstance.MountGear(
        instanceId = record.instanceId,
        agentId = AgentId(record.agentId),
        itemId = ItemId(record.itemId),
        rarity = Rarity.valueOf(requireNotNull(record.rarity) { "MOUNT_GEAR row ${record.instanceId} missing rarity" }),
        durabilityCurrent = requireNotNull(record.durabilityCurrent) { "MOUNT_GEAR row ${record.instanceId} missing durability_current" },
        durabilityMax = requireNotNull(record.durabilityMax) { "MOUNT_GEAR row ${record.instanceId} missing durability_max" },
        creatorAgentId = record.creatorAgentId?.let(::AgentId),
        createdAtTick = record.createdAtTick,
        equippedOnMount = record.equippedOnMountId,
        equippedMountSlot = record.equippedMountSlot?.let(MountSlot::valueOf),
    )
}
