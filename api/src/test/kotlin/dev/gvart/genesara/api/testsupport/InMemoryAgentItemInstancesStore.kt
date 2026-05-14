package dev.gvart.genesara.api.testsupport

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.AgentItemInstancesStore
import dev.gvart.genesara.world.EquipSlot
import dev.gvart.genesara.world.ItemInstance
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
}
