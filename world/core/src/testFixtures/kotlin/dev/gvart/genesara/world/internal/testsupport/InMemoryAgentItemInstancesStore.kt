package dev.gvart.genesara.world.internal.testsupport

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.AgentItemInstancesStore
import dev.gvart.genesara.world.EquipSlot
import dev.gvart.genesara.world.ItemInstance
import dev.gvart.genesara.world.MountId
import dev.gvart.genesara.world.MountSlot
import java.util.UUID

/**
 * Test stub for [AgentItemInstancesStore] backed by an in-memory map. Open so
 * specific tests can override hot-spot methods (e.g. capture inserts for
 * assertion). Methods that mutate the underlying map are thread-unsafe; tests
 * exercise reducers single-threaded.
 *
 * **Mirrored** at `api/src/test/.../api/testsupport/InMemoryAgentItemInstancesStore.kt`
 * because world test-sources are not on api's test classpath. Keep both in sync
 * until `:world` gains a `java-test-fixtures` configuration.
 */
open class InMemoryAgentItemInstancesStore : AgentItemInstancesStore {

    val byId: MutableMap<UUID, ItemInstance> = mutableMapOf()
    private val _inserted: MutableList<ItemInstance> = mutableListOf()

    /** All instances inserted via [insert] in insertion order. Seeded instances do NOT appear here. */
    val inserted: List<ItemInstance> get() = _inserted

    /** Convenience filter — equipment-only insertions in insertion order. */
    val insertedEquipment: List<ItemInstance.Equipment>
        get() = _inserted.filterIsInstance<ItemInstance.Equipment>()

    /** Convenience filter — key-only insertions in insertion order. */
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
}
