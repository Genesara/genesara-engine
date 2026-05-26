package dev.gvart.genesara.api.testsupport

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.AgentItemInstancesStore
import dev.gvart.genesara.world.EquipSlot
import dev.gvart.genesara.world.ItemInstance
import dev.gvart.genesara.world.MountId
import dev.gvart.genesara.world.MountSlot
import dev.gvart.genesara.world.Rarity
import java.util.UUID

/**
 * Test stub for [AgentItemInstancesStore] backed by an in-memory map. Mirrors
 * the world-module helper at `world.internal.testsupport.InMemoryAgentItemInstancesStore`;
 * duplicated because world test-sources are not on the api test classpath.
 */
open class InMemoryAgentItemInstancesStore : AgentItemInstancesStore {

    val byId: MutableMap<UUID, ItemInstance> = mutableMapOf()
    private val _inserted: MutableList<ItemInstance> = mutableListOf()

    /** Instances inserted via [insert] in insertion order. Seeded instances do NOT appear. */
    val inserted: List<ItemInstance> get() = _inserted
    val insertedEquipment: List<ItemInstance.Equipment>
        get() = _inserted.filterIsInstance<ItemInstance.Equipment>()
    val insertedKeys: List<ItemInstance.Key>
        get() = _inserted.filterIsInstance<ItemInstance.Key>()

    fun seed(instance: ItemInstance) {
        byId[instance.instanceId] = instance
    }

    override fun insert(instance: ItemInstance) {
        byId[instance.instanceId] = instance
        _inserted += instance
    }

    override fun findById(instanceId: UUID): ItemInstance? = byId[instanceId]

    override fun listByAgent(agentId: AgentId): List<ItemInstance> =
        byId.values
            .filter { it.agentId == agentId }
            .sortedWith(compareBy({ it.createdAtTick }, { it.instanceId }))

    override fun delete(instanceId: UUID): Boolean = byId.remove(instanceId) != null

    override fun updateEquipment(
        instanceId: UUID,
        rarity: Rarity?,
        durabilityCurrent: Int?,
        durabilityMax: Int?,
    ): ItemInstance.Equipment? {
        val current = byId[instanceId] as? ItemInstance.Equipment ?: return null
        val updated = current.copy(
            rarity = rarity ?: current.rarity,
            durabilityCurrent = durabilityCurrent ?: current.durabilityCurrent,
            durabilityMax = durabilityMax ?: current.durabilityMax,
        )
        byId[instanceId] = updated
        return updated
    }

    override fun equippedFor(agentId: AgentId): Map<EquipSlot, ItemInstance.Equipment> =
        byId.values
            .filterIsInstance<ItemInstance.Equipment>()
            .filter { it.agentId == agentId && it.equippedInSlot != null }
            .associateBy { it.equippedInSlot!! }

    override fun assignToSlot(instanceId: UUID, agentId: AgentId, slot: EquipSlot): ItemInstance.Equipment? {
        val current = byId[instanceId] as? ItemInstance.Equipment ?: return null
        if (current.agentId != agentId) return null
        val updated = current.copy(equippedInSlot = slot)
        byId[instanceId] = updated
        return updated
    }

    override fun clearSlot(agentId: AgentId, slot: EquipSlot): ItemInstance.Equipment? {
        val match = byId.values
            .filterIsInstance<ItemInstance.Equipment>()
            .firstOrNull { it.agentId == agentId && it.equippedInSlot == slot }
            ?: return null
        val updated = match.copy(equippedInSlot = null)
        byId[match.instanceId] = updated
        return updated
    }

    override fun decrementDurability(instanceId: UUID, amount: Int): ItemInstance.Equipment? {
        require(amount >= 0) { "decrement amount must be non-negative, got $amount" }
        val current = byId[instanceId] as? ItemInstance.Equipment ?: return null
        val next = (current.durabilityCurrent - amount).coerceAtLeast(0)
        val updated = current.copy(durabilityCurrent = next)
        byId[instanceId] = updated
        return updated
    }

    override fun agentHoldsKeyFor(agent: AgentId, gateInstanceId: UUID): Boolean =
        byId.values
            .filterIsInstance<ItemInstance.Key>()
            .any { it.agentId == agent && it.gateInstanceId == gateInstanceId }

    override fun assignToMountSlot(
        instanceId: UUID,
        agentId: AgentId,
        mountId: MountId,
        slot: MountSlot,
    ): ItemInstance.MountGear? {
        val current = byId[instanceId] as? ItemInstance.MountGear ?: return null
        if (current.agentId != agentId) return null
        val updated = current.copy(equippedOnMount = mountId.value, equippedMountSlot = slot)
        byId[instanceId] = updated
        return updated
    }

    override fun clearMountSlot(mountId: MountId, slot: MountSlot): ItemInstance.MountGear? {
        val match = byId.values
            .filterIsInstance<ItemInstance.MountGear>()
            .firstOrNull { it.equippedOnMount == mountId.value && it.equippedMountSlot == slot }
            ?: return null
        val updated = match.copy(equippedOnMount = null, equippedMountSlot = null)
        byId[match.instanceId] = updated
        return updated
    }

    override fun byEquippedOnMount(mountId: MountId): List<ItemInstance.MountGear> =
        byId.values
            .filterIsInstance<ItemInstance.MountGear>()
            .filter { it.equippedOnMount == mountId.value }

    override fun stowOnMount(instanceId: UUID, agentId: AgentId, mountId: MountId): ItemInstance? {
        val current = byId[instanceId] ?: return null
        if (current.agentId != agentId) return null
        val equipped = when (current) {
            is ItemInstance.Equipment -> current.equippedInSlot != null
            is ItemInstance.MountGear -> current.equippedOnMount != null
            is ItemInstance.Key -> false
        }
        if (equipped) return null
        val updated = current.withStowedInMountId(mountId.value)
        byId[instanceId] = updated
        return updated
    }

    override fun unstowFromMount(instanceId: UUID): ItemInstance? {
        val current = byId[instanceId] ?: return null
        val updated = current.withStowedInMountId(null)
        byId[instanceId] = updated
        return updated
    }

    override fun byStowedOnMount(mountId: MountId): List<ItemInstance> =
        byId.values.filter { it.stowedInMountId == mountId.value }

    override fun gearOnMount(mountId: MountId, slot: MountSlot): ItemInstance.MountGear? =
        byId.values
            .filterIsInstance<ItemInstance.MountGear>()
            .firstOrNull { it.equippedOnMount == mountId.value && it.equippedMountSlot == slot }

    override fun reassignOwner(instanceId: UUID, fromAgent: AgentId, toAgent: AgentId): ItemInstance? {
        val current = byId[instanceId] ?: return null
        if (current.agentId != fromAgent) return null
        val equipped = when (current) {
            is ItemInstance.Equipment -> current.equippedInSlot != null
            is ItemInstance.MountGear -> current.equippedOnMount != null
            is ItemInstance.Key -> false
        }
        if (equipped) return null
        if (current.stowedInMountId != null) return null
        val updated = current.withAgentId(toAgent)
        byId[instanceId] = updated
        return updated
    }
}

private fun ItemInstance.withStowedInMountId(value: UUID?): ItemInstance = when (this) {
    is ItemInstance.Equipment -> copy(stowedInMountId = value)
    is ItemInstance.Key -> copy(stowedInMountId = value)
    is ItemInstance.MountGear -> copy(stowedInMountId = value)
}

private fun ItemInstance.withAgentId(value: AgentId): ItemInstance = when (this) {
    is ItemInstance.Equipment -> copy(agentId = value)
    is ItemInstance.Key -> copy(agentId = value)
    is ItemInstance.MountGear -> copy(agentId = value)
}
