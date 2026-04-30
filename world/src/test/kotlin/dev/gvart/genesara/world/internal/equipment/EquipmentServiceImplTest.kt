package dev.gvart.genesara.world.internal.equipment

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.EquipRejection
import dev.gvart.genesara.world.EquipResult
import dev.gvart.genesara.world.EquipSlot
import dev.gvart.genesara.world.EquipmentInstance
import dev.gvart.genesara.world.EquipmentInstanceStore
import dev.gvart.genesara.world.Item
import dev.gvart.genesara.world.ItemCategory
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.ItemLookup
import dev.gvart.genesara.world.Rarity
import dev.gvart.genesara.world.UnequipResult
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import java.sql.SQLException
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Unit coverage for [EquipmentServiceImpl]. Stubs the store and item lookup so
 * every validation branch fires without touching the database. The
 * `JooqEquipmentInstanceStoreIntegrationTest` separately verifies the SQL-level
 * unique-slot index that backs the equip race; this file pins the rejection
 * priority an agent observes through the MCP layer.
 */
class EquipmentServiceImplTest {

    private val agent = AgentId(UUID.randomUUID())
    private val otherAgent = AgentId(UUID.randomUUID())

    private val swordId = ItemId("RUSTY_SWORD")
    private val greatswordId = ItemId("IRON_GREATSWORD")
    private val helmetId = ItemId("LEATHER_HELMET")
    private val resourceId = ItemId("WOOD")

    private val sword = equipmentItem(swordId, validSlots = setOf(EquipSlot.MAIN_HAND), twoHanded = false)
    private val greatsword = equipmentItem(greatswordId, validSlots = setOf(EquipSlot.MAIN_HAND), twoHanded = true)
    private val helmet = equipmentItem(helmetId, validSlots = setOf(EquipSlot.HELMET), twoHanded = false)
    private val wood = Item(
        id = resourceId,
        displayName = "Wood",
        description = "",
        category = ItemCategory.RESOURCE,
        weightPerUnit = 100,
        maxStack = 100,
    )

    private val items = StubItemLookup(mapOf(swordId to sword, greatswordId to greatsword, helmetId to helmet, resourceId to wood))

    @Test
    fun `equip moves the instance into the slot and returns the updated instance`() {
        val instance = unequipped(swordId)
        val store = StubStore(initial = listOf(instance))
        val service = EquipmentServiceImpl(store, items)

        val result = service.equip(agent, instance.instanceId, EquipSlot.MAIN_HAND)

        val equipped = assertIs<EquipResult.Equipped>(result)
        assertEquals(EquipSlot.MAIN_HAND, equipped.instance.equippedInSlot)
        assertEquals(EquipSlot.MAIN_HAND, store.equippedFor(agent)[EquipSlot.MAIN_HAND]?.equippedInSlot)
    }

    @Test
    fun `equip rejects when no instance has the given id`() {
        val service = EquipmentServiceImpl(StubStore(), items)
        val result = service.equip(agent, UUID.randomUUID(), EquipSlot.MAIN_HAND)
        assertEquals(EquipRejection.INSTANCE_NOT_FOUND, (result as EquipResult.Rejected).reason)
    }

    @Test
    fun `equip rejects when the instance belongs to a different agent`() {
        val instance = unequipped(swordId, owner = otherAgent)
        val service = EquipmentServiceImpl(StubStore(listOf(instance)), items)

        val result = service.equip(agent, instance.instanceId, EquipSlot.MAIN_HAND)

        assertEquals(EquipRejection.NOT_YOUR_INSTANCE, (result as EquipResult.Rejected).reason)
    }

    @Test
    fun `equip rejects when the catalog has no entry for the instance's item`() {
        val phantomId = ItemId("PHANTOM")
        val instance = unequipped(phantomId)
        val service = EquipmentServiceImpl(StubStore(listOf(instance)), items)

        val result = service.equip(agent, instance.instanceId, EquipSlot.MAIN_HAND)

        assertEquals(EquipRejection.UNKNOWN_ITEM, (result as EquipResult.Rejected).reason)
    }

    @Test
    fun `equip rejects when the item is a stackable resource (not equipment)`() {
        val instance = unequipped(resourceId)
        val service = EquipmentServiceImpl(StubStore(listOf(instance)), items)

        val result = service.equip(agent, instance.instanceId, EquipSlot.MAIN_HAND)

        assertEquals(EquipRejection.NOT_EQUIPMENT, (result as EquipResult.Rejected).reason)
    }

    @Test
    fun `equip rejects when the slot is not valid for the item`() {
        val instance = unequipped(helmetId)
        val service = EquipmentServiceImpl(StubStore(listOf(instance)), items)

        val result = service.equip(agent, instance.instanceId, EquipSlot.MAIN_HAND)

        assertEquals(EquipRejection.INVALID_SLOT_FOR_ITEM, (result as EquipResult.Rejected).reason)
    }

    @Test
    fun `equip rejects two-handed weapon to a non-MAIN_HAND slot when catalog allows it`() {
        // The Item init invariant blocks the realistic mistake (two-handed +
        // OFF_HAND in validSlots), so this test uses a less-realistic but
        // still-valid catalog entry: a two-handed item with MAIN_HAND + HELMET
        // as its valid slots. Trying to equip to HELMET passes the slot-validity
        // check but trips the TWO_HANDED_NOT_MAIN_HAND defensive guard.
        val odd = equipmentItem(
            ItemId("ODDITY"),
            validSlots = setOf(EquipSlot.MAIN_HAND, EquipSlot.HELMET),
            twoHanded = true,
        )
        val items = StubItemLookup(mapOf(odd.id to odd))
        val instance = unequipped(odd.id)
        val service = EquipmentServiceImpl(StubStore(listOf(instance)), items)

        val result = service.equip(agent, instance.instanceId, EquipSlot.HELMET)

        assertEquals(EquipRejection.TWO_HANDED_NOT_MAIN_HAND, (result as EquipResult.Rejected).reason)
    }

    @Test
    fun `equip rejects when the same instance is already in another slot`() {
        val instance = unequipped(swordId).copy(equippedInSlot = EquipSlot.MAIN_HAND)
        val service = EquipmentServiceImpl(StubStore(listOf(instance)), items)

        val result = service.equip(agent, instance.instanceId, EquipSlot.MAIN_HAND)

        assertEquals(EquipRejection.ALREADY_EQUIPPED, (result as EquipResult.Rejected).reason)
    }

    @Test
    fun `equipping a two-handed weapon while OFF_HAND has an item rejects with OFF_HAND_OCCUPIED`() {
        val offHandItem = equipmentItem(ItemId("DAGGER"), validSlots = setOf(EquipSlot.OFF_HAND), twoHanded = false)
        val items = StubItemLookup(mapOf(greatswordId to greatsword, offHandItem.id to offHandItem))
        val greatswordInstance = unequipped(greatswordId)
        val daggerInstance = unequipped(offHandItem.id, slot = EquipSlot.OFF_HAND)
        val service = EquipmentServiceImpl(StubStore(listOf(greatswordInstance, daggerInstance)), items)

        val result = service.equip(agent, greatswordInstance.instanceId, EquipSlot.MAIN_HAND)

        assertEquals(EquipRejection.OFF_HAND_OCCUPIED, (result as EquipResult.Rejected).reason)
    }

    @Test
    fun `equipping anything to OFF_HAND while a two-handed is in MAIN_HAND rejects`() {
        val offHandItem = equipmentItem(ItemId("DAGGER"), validSlots = setOf(EquipSlot.OFF_HAND), twoHanded = false)
        val items = StubItemLookup(mapOf(greatswordId to greatsword, offHandItem.id to offHandItem))
        val greatswordInstance = unequipped(greatswordId, slot = EquipSlot.MAIN_HAND)
        val daggerInstance = unequipped(offHandItem.id)
        val service = EquipmentServiceImpl(StubStore(listOf(greatswordInstance, daggerInstance)), items)

        val result = service.equip(agent, daggerInstance.instanceId, EquipSlot.OFF_HAND)

        assertEquals(EquipRejection.OFF_HAND_BLOCKED_BY_TWO_HANDED, (result as EquipResult.Rejected).reason)
    }

    @Test
    fun `equip rejects when the target slot already holds a different instance`() {
        val a = unequipped(swordId, slot = EquipSlot.MAIN_HAND)
        val b = unequipped(swordId)
        val service = EquipmentServiceImpl(StubStore(listOf(a, b)), items)

        val result = service.equip(agent, b.instanceId, EquipSlot.MAIN_HAND)

        assertEquals(EquipRejection.SLOT_OCCUPIED, (result as EquipResult.Rejected).reason)
    }

    @Test
    fun `equip translates a unique-index violation from assignToSlot to SLOT_OCCUPIED`() {
        // Simulates the race window: pre-checks pass (the stub equippedFor is
        // empty), but assignToSlot's SQL throws the unique-violation that
        // would happen under concurrent equip in production. The service must
        // translate it to a structured rejection rather than letting the
        // DataIntegrityViolationException leak to the agent.
        val instance = unequipped(swordId)
        val store = ThrowingStore(rows = listOf(instance), throwOnAssign = uniqueViolation())
        val service = EquipmentServiceImpl(store, items)

        val result = service.equip(agent, instance.instanceId, EquipSlot.MAIN_HAND)

        assertEquals(EquipRejection.SLOT_OCCUPIED, (result as EquipResult.Rejected).reason)
    }

    @Test
    fun `non-unique DataIntegrityViolation propagates rather than masking a different bug`() {
        // CHECK constraint violations (e.g. an unknown slot string) shouldn't
        // be silently translated to SLOT_OCCUPIED — they're real bugs.
        val instance = unequipped(swordId)
        val store = ThrowingStore(rows = listOf(instance), throwOnAssign = checkViolation())
        val service = EquipmentServiceImpl(store, items)

        try {
            service.equip(agent, instance.instanceId, EquipSlot.MAIN_HAND)
            error("expected the check-constraint violation to propagate")
        } catch (ex: DataIntegrityViolationException) {
            // Expected: not translated.
        }
    }

    private fun uniqueViolation(): DataIntegrityViolationException =
        DataIntegrityViolationException("unique violation", SQLException("duplicate", "23505"))

    private fun checkViolation(): DataIntegrityViolationException =
        DataIntegrityViolationException("check violation", SQLException("bad value", "23514"))

    @Test
    fun `unequip returns the cleared instance when the slot is filled`() {
        val instance = unequipped(swordId, slot = EquipSlot.MAIN_HAND)
        val store = StubStore(listOf(instance))
        val service = EquipmentServiceImpl(store, items)

        val result = service.unequip(agent, EquipSlot.MAIN_HAND)

        val unequipped = assertIs<UnequipResult.Unequipped>(result)
        assertEquals(null, unequipped.instance.equippedInSlot)
        assertEquals(emptyMap(), store.equippedFor(agent))
    }

    @Test
    fun `unequip returns SlotEmpty when the slot has no item`() {
        val service = EquipmentServiceImpl(StubStore(), items)

        val result = service.unequip(agent, EquipSlot.MAIN_HAND)

        assertIs<UnequipResult.SlotEmpty>(result)
    }

    @Test
    fun `equippedFor returns the slot map`() {
        val a = unequipped(swordId, slot = EquipSlot.MAIN_HAND)
        val b = unequipped(helmetId, slot = EquipSlot.HELMET)
        val service = EquipmentServiceImpl(StubStore(listOf(a, b)), items)

        val map = service.equippedFor(agent)

        assertEquals(setOf(EquipSlot.MAIN_HAND, EquipSlot.HELMET), map.keys)
    }

    // ─────────────────────── helpers ───────────────────────

    private fun equipmentItem(
        id: ItemId,
        validSlots: Set<EquipSlot>,
        twoHanded: Boolean = false,
    ) = Item(
        id = id,
        displayName = id.value,
        description = "",
        category = ItemCategory.EQUIPMENT,
        weightPerUnit = 1000,
        maxStack = 1,
        regenerating = false,
        maxDurability = 100,
        validSlots = validSlots,
        twoHanded = twoHanded,
    )

    private fun unequipped(
        itemId: ItemId,
        owner: AgentId = agent,
        slot: EquipSlot? = null,
    ) = EquipmentInstance(
        instanceId = UUID.randomUUID(),
        agentId = owner,
        itemId = itemId,
        rarity = Rarity.COMMON,
        durabilityCurrent = 100,
        durabilityMax = 100,
        creatorAgentId = null,
        createdAtTick = 1L,
        equippedInSlot = slot,
    )

    private class StubItemLookup(private val byId: Map<ItemId, Item>) : ItemLookup {
        override fun byId(id: ItemId): Item? = byId[id]
        override fun all(): List<Item> = byId.values.toList()
    }

    /**
     * In-memory stub. Mutations are not transactional; that's fine — the unit
     * tests run in a single thread, and the SQL-level race fence is verified
     * separately in the integration test.
     */
    private class StubStore(initial: List<EquipmentInstance> = emptyList()) : EquipmentInstanceStore {
        private val rows: MutableMap<UUID, EquipmentInstance> = initial.associateBy { it.instanceId }.toMutableMap()

        override fun insert(instance: EquipmentInstance) {
            check(instance.instanceId !in rows) { "duplicate instance id" }
            rows[instance.instanceId] = instance
        }

        override fun findById(instanceId: UUID): EquipmentInstance? = rows[instanceId]

        override fun listByAgent(agentId: AgentId): List<EquipmentInstance> =
            rows.values.filter { it.agentId == agentId }.sortedBy { it.instanceId }

        override fun equippedFor(agentId: AgentId): Map<EquipSlot, EquipmentInstance> =
            rows.values
                .filter { it.agentId == agentId && it.equippedInSlot != null }
                .associateBy { it.equippedInSlot!! }

        override fun assignToSlot(instanceId: UUID, agentId: AgentId, slot: EquipSlot): EquipmentInstance? {
            val current = rows[instanceId]?.takeIf { it.agentId == agentId } ?: return null
            val updated = current.copy(equippedInSlot = slot)
            rows[instanceId] = updated
            return updated
        }

        override fun clearSlot(agentId: AgentId, slot: EquipSlot): EquipmentInstance? {
            val target = rows.values.firstOrNull { it.agentId == agentId && it.equippedInSlot == slot }
                ?: return null
            val updated = target.copy(equippedInSlot = null)
            rows[target.instanceId] = updated
            return updated
        }

        override fun decrementDurability(instanceId: UUID, amount: Int): EquipmentInstance? {
            val current = rows[instanceId] ?: return null
            val newCurrent = (current.durabilityCurrent - amount).coerceAtLeast(0)
            val updated = current.copy(durabilityCurrent = newCurrent)
            rows[instanceId] = updated
            return updated
        }

        override fun delete(instanceId: UUID): Boolean = rows.remove(instanceId) != null
    }

    /**
     * Variant of [StubStore] that throws on `assignToSlot` to simulate the
     * SQL-level integrity violations the production index produces under race.
     */
    private class ThrowingStore(
        rows: List<EquipmentInstance>,
        private val throwOnAssign: DataIntegrityViolationException,
    ) : EquipmentInstanceStore {
        private val byId = rows.associateBy { it.instanceId }
        override fun insert(instance: EquipmentInstance) = error("not used")
        override fun findById(instanceId: UUID): EquipmentInstance? = byId[instanceId]
        override fun listByAgent(agentId: AgentId): List<EquipmentInstance> =
            byId.values.filter { it.agentId == agentId }
        override fun equippedFor(agentId: AgentId): Map<EquipSlot, EquipmentInstance> = emptyMap()
        override fun assignToSlot(instanceId: UUID, agentId: AgentId, slot: EquipSlot): EquipmentInstance? {
            throw throwOnAssign
        }
        override fun clearSlot(agentId: AgentId, slot: EquipSlot): EquipmentInstance? = null
        override fun decrementDurability(instanceId: UUID, amount: Int): EquipmentInstance? = null
        override fun delete(instanceId: UUID): Boolean = false
    }
}
