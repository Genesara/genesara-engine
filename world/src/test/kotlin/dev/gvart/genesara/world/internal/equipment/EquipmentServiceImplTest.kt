package dev.gvart.genesara.world.internal.equipment

import dev.gvart.genesara.account.PlayerId
import dev.gvart.genesara.player.Agent
import dev.gvart.genesara.player.AgentAttributes
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.player.AgentSkillState
import dev.gvart.genesara.player.AgentSkillsRegistry
import dev.gvart.genesara.player.AgentSkillsSnapshot
import dev.gvart.genesara.player.Attribute
import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.player.SkillSlotError
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    /**
     * Default permissive agent + skills stubs. Tests for the C1 surface (slot
     * routing, two-handed lock, occupancy) don't care about attributes /
     * skills, so the defaults register the test agent with caps high enough
     * to pass any reasonable requirement and an empty skill snapshot.
     * Requirement-specific tests build their own narrower stubs.
     */
    private val agents: AgentRegistry = StubAgentRegistry(mapOf(agent to agentWith()))
    private val skills: AgentSkillsRegistry = StubSkillsRegistry()

    @Test
    fun `equip moves the instance into the slot and returns the updated instance`() {
        val instance = unequipped(swordId)
        val store = StubStore(initial = listOf(instance))
        val service = EquipmentServiceImpl(store, items, agents, skills)

        val result = service.equip(agent, instance.instanceId, EquipSlot.MAIN_HAND)

        val equipped = assertIs<EquipResult.Equipped>(result)
        assertEquals(EquipSlot.MAIN_HAND, equipped.instance.equippedInSlot)
        assertEquals(EquipSlot.MAIN_HAND, store.equippedFor(agent)[EquipSlot.MAIN_HAND]?.equippedInSlot)
    }

    @Test
    fun `equip rejects when no instance has the given id`() {
        val service = EquipmentServiceImpl(StubStore(), items, agents, skills)
        val result = service.equip(agent, UUID.randomUUID(), EquipSlot.MAIN_HAND)
        assertEquals(EquipRejection.INSTANCE_NOT_FOUND, (result as EquipResult.Rejected).reason)
    }

    @Test
    fun `equip rejects when the instance belongs to a different agent`() {
        val instance = unequipped(swordId, owner = otherAgent)
        val service = EquipmentServiceImpl(StubStore(listOf(instance)), items, agents, skills)

        val result = service.equip(agent, instance.instanceId, EquipSlot.MAIN_HAND)

        assertEquals(EquipRejection.NOT_YOUR_INSTANCE, (result as EquipResult.Rejected).reason)
    }

    @Test
    fun `equip rejects when the catalog has no entry for the instance's item`() {
        val phantomId = ItemId("PHANTOM")
        val instance = unequipped(phantomId)
        val service = EquipmentServiceImpl(StubStore(listOf(instance)), items, agents, skills)

        val result = service.equip(agent, instance.instanceId, EquipSlot.MAIN_HAND)

        assertEquals(EquipRejection.UNKNOWN_ITEM, (result as EquipResult.Rejected).reason)
    }

    @Test
    fun `equip rejects when the item is a stackable resource (not equipment)`() {
        val instance = unequipped(resourceId)
        val service = EquipmentServiceImpl(StubStore(listOf(instance)), items, agents, skills)

        val result = service.equip(agent, instance.instanceId, EquipSlot.MAIN_HAND)

        assertEquals(EquipRejection.NOT_EQUIPMENT, (result as EquipResult.Rejected).reason)
    }

    @Test
    fun `equip rejects when the slot is not valid for the item`() {
        val instance = unequipped(helmetId)
        val service = EquipmentServiceImpl(StubStore(listOf(instance)), items, agents, skills)

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
        val service = EquipmentServiceImpl(StubStore(listOf(instance)), items, agents, skills)

        val result = service.equip(agent, instance.instanceId, EquipSlot.HELMET)

        assertEquals(EquipRejection.TWO_HANDED_NOT_MAIN_HAND, (result as EquipResult.Rejected).reason)
    }

    @Test
    fun `equip rejects when the same instance is already in another slot`() {
        val instance = unequipped(swordId).copy(equippedInSlot = EquipSlot.MAIN_HAND)
        val service = EquipmentServiceImpl(StubStore(listOf(instance)), items, agents, skills)

        val result = service.equip(agent, instance.instanceId, EquipSlot.MAIN_HAND)

        assertEquals(EquipRejection.ALREADY_EQUIPPED, (result as EquipResult.Rejected).reason)
    }

    @Test
    fun `equipping a two-handed weapon while OFF_HAND has an item rejects with OFF_HAND_OCCUPIED`() {
        val offHandItem = equipmentItem(ItemId("DAGGER"), validSlots = setOf(EquipSlot.OFF_HAND), twoHanded = false)
        val items = StubItemLookup(mapOf(greatswordId to greatsword, offHandItem.id to offHandItem))
        val greatswordInstance = unequipped(greatswordId)
        val daggerInstance = unequipped(offHandItem.id, slot = EquipSlot.OFF_HAND)
        val service = EquipmentServiceImpl(StubStore(listOf(greatswordInstance, daggerInstance)), items, agents, skills)

        val result = service.equip(agent, greatswordInstance.instanceId, EquipSlot.MAIN_HAND)

        assertEquals(EquipRejection.OFF_HAND_OCCUPIED, (result as EquipResult.Rejected).reason)
    }

    @Test
    fun `equipping anything to OFF_HAND while a two-handed is in MAIN_HAND rejects`() {
        val offHandItem = equipmentItem(ItemId("DAGGER"), validSlots = setOf(EquipSlot.OFF_HAND), twoHanded = false)
        val items = StubItemLookup(mapOf(greatswordId to greatsword, offHandItem.id to offHandItem))
        val greatswordInstance = unequipped(greatswordId, slot = EquipSlot.MAIN_HAND)
        val daggerInstance = unequipped(offHandItem.id)
        val service = EquipmentServiceImpl(StubStore(listOf(greatswordInstance, daggerInstance)), items, agents, skills)

        val result = service.equip(agent, daggerInstance.instanceId, EquipSlot.OFF_HAND)

        assertEquals(EquipRejection.OFF_HAND_BLOCKED_BY_TWO_HANDED, (result as EquipResult.Rejected).reason)
    }

    @Test
    fun `equip rejects when the target slot already holds a different instance`() {
        val a = unequipped(swordId, slot = EquipSlot.MAIN_HAND)
        val b = unequipped(swordId)
        val service = EquipmentServiceImpl(StubStore(listOf(a, b)), items, agents, skills)

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
        val service = EquipmentServiceImpl(store, items, agents, skills)

        val result = service.equip(agent, instance.instanceId, EquipSlot.MAIN_HAND)

        assertEquals(EquipRejection.SLOT_OCCUPIED, (result as EquipResult.Rejected).reason)
    }

    @Test
    fun `non-unique DataIntegrityViolation propagates rather than masking a different bug`() {
        // CHECK constraint violations (e.g. an unknown slot string) shouldn't
        // be silently translated to SLOT_OCCUPIED — they're real bugs.
        val instance = unequipped(swordId)
        val store = ThrowingStore(rows = listOf(instance), throwOnAssign = checkViolation())
        val service = EquipmentServiceImpl(store, items, agents, skills)

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

    // ─────────────────────── Requirement gates (Slice C2) ───────────────────────

    @Test
    fun `equip succeeds when the agent meets every attribute requirement`() {
        val gated = equipmentItem(
            ItemId("HEAVY_BLADE"),
            validSlots = setOf(EquipSlot.MAIN_HAND),
            requiredAttributes = mapOf(Attribute.STRENGTH to 10, Attribute.DEXTERITY to 5),
        )
        val items = StubItemLookup(mapOf(gated.id to gated))
        val agents = StubAgentRegistry(mapOf(agent to agentWith(strength = 12, dexterity = 8)))
        val instance = unequipped(gated.id)
        val service = EquipmentServiceImpl(StubStore(listOf(instance)), items, agents, skills)

        val result = service.equip(agent, instance.instanceId, EquipSlot.MAIN_HAND)

        assertIs<EquipResult.Equipped>(result)
    }

    @Test
    fun `equip rejects with INSUFFICIENT_ATTRIBUTES on a single-attribute shortfall`() {
        val gated = equipmentItem(
            ItemId("HEAVY_BLADE"),
            validSlots = setOf(EquipSlot.MAIN_HAND),
            requiredAttributes = mapOf(Attribute.STRENGTH to 12),
        )
        val items = StubItemLookup(mapOf(gated.id to gated))
        val agents = StubAgentRegistry(mapOf(agent to agentWith(strength = 8)))
        val instance = unequipped(gated.id)
        val service = EquipmentServiceImpl(StubStore(listOf(instance)), items, agents, skills)

        val result = service.equip(agent, instance.instanceId, EquipSlot.MAIN_HAND)

        val rejected = assertIs<EquipResult.Rejected>(result)
        assertEquals(EquipRejection.INSUFFICIENT_ATTRIBUTES, rejected.reason)
        // Detail string must name the failing attribute, the floor, and the agent's actual value.
        val detail = assertNotNull(rejected.detail)
        assertTrue("STRENGTH" in detail, "detail should mention failing attribute, got: $detail")
        assertTrue("12" in detail, "detail should mention required floor 12, got: $detail")
        assertTrue("8" in detail, "detail should mention agent's value 8, got: $detail")
    }

    @Test
    fun `equip rejects on the first failing attribute when several would fail`() {
        // Stable iteration order on a LinkedHashMap means STRENGTH is checked
        // first; the agent fails both but only the first failure is reported.
        val gated = equipmentItem(
            ItemId("HEAVY_BLADE"),
            validSlots = setOf(EquipSlot.MAIN_HAND),
            requiredAttributes = linkedMapOf(Attribute.STRENGTH to 12, Attribute.DEXTERITY to 12),
        )
        val items = StubItemLookup(mapOf(gated.id to gated))
        val agents = StubAgentRegistry(mapOf(agent to agentWith(strength = 5, dexterity = 5)))
        val instance = unequipped(gated.id)
        val service = EquipmentServiceImpl(StubStore(listOf(instance)), items, agents, skills)

        val result = service.equip(agent, instance.instanceId, EquipSlot.MAIN_HAND)

        val rejected = assertIs<EquipResult.Rejected>(result)
        assertEquals(EquipRejection.INSUFFICIENT_ATTRIBUTES, rejected.reason)
        assertTrue("STRENGTH" in rejected.detail!!)
    }

    @Test
    fun `equip succeeds when the agent meets every skill requirement`() {
        val gated = equipmentItem(
            ItemId("ENCHANTED_BOW"),
            validSlots = setOf(EquipSlot.MAIN_HAND),
            requiredSkills = mapOf("ARCHERY" to 30),
        )
        val items = StubItemLookup(mapOf(gated.id to gated))
        val skills = StubSkillsRegistry.withLevel("ARCHERY" to 35)
        val instance = unequipped(gated.id)
        val service = EquipmentServiceImpl(StubStore(listOf(instance)), items, agents, skills)

        val result = service.equip(agent, instance.instanceId, EquipSlot.MAIN_HAND)

        assertIs<EquipResult.Equipped>(result)
    }

    @Test
    fun `equip rejects with INSUFFICIENT_SKILLS when the trained level is too low`() {
        val gated = equipmentItem(
            ItemId("ENCHANTED_BOW"),
            validSlots = setOf(EquipSlot.MAIN_HAND),
            requiredSkills = mapOf("ARCHERY" to 30),
        )
        val items = StubItemLookup(mapOf(gated.id to gated))
        val skills = StubSkillsRegistry.withLevel("ARCHERY" to 10)
        val instance = unequipped(gated.id)
        val service = EquipmentServiceImpl(StubStore(listOf(instance)), items, agents, skills)

        val result = service.equip(agent, instance.instanceId, EquipSlot.MAIN_HAND)

        val rejected = assertIs<EquipResult.Rejected>(result)
        assertEquals(EquipRejection.INSUFFICIENT_SKILLS, rejected.reason)
        val detail = assertNotNull(rejected.detail)
        assertTrue("ARCHERY" in detail)
        assertTrue("30" in detail)
        assertTrue("10" in detail)
    }

    @Test
    fun `a skill the agent has never trained reads as level 0 and trips the rejection`() {
        val gated = equipmentItem(
            ItemId("ENCHANTED_BOW"),
            validSlots = setOf(EquipSlot.MAIN_HAND),
            requiredSkills = mapOf("NEVER_TRAINED" to 5),
        )
        val items = StubItemLookup(mapOf(gated.id to gated))
        val instance = unequipped(gated.id)
        val service = EquipmentServiceImpl(StubStore(listOf(instance)), items, agents, skills)

        val result = service.equip(agent, instance.instanceId, EquipSlot.MAIN_HAND)

        val rejected = assertIs<EquipResult.Rejected>(result)
        assertEquals(EquipRejection.INSUFFICIENT_SKILLS, rejected.reason)
        // `(you have 0)` confirms the never-trained branch took the level-0 default.
        assertTrue("0" in rejected.detail!!, "detail should report level 0, got: ${rejected.detail}")
    }

    @Test
    fun `requirement check is skipped when an item declares no prerequisites`() {
        // Pin the hot-path optimization: items without requirements (the common
        // case in v1) shouldn't even consult AgentRegistry. We use a registry
        // that throws on any access to prove the equip path doesn't read it.
        val gated = equipmentItem(
            ItemId("PLAIN_RING"),
            validSlots = setOf(EquipSlot.RING_LEFT),
        )
        val items = StubItemLookup(mapOf(gated.id to gated))
        val poisonAgents = object : AgentRegistry {
            override fun find(id: AgentId): Agent? = error("AgentRegistry.find should not be called")
            override fun listForOwner(owner: PlayerId): List<Agent> = error("not used")
        }
        val instance = unequipped(gated.id)
        val service = EquipmentServiceImpl(StubStore(listOf(instance)), items, poisonAgents, skills)

        val result = service.equip(agent, instance.instanceId, EquipSlot.RING_LEFT)

        assertIs<EquipResult.Equipped>(result)
    }

    @Test
    fun `attribute requirements are checked before skill requirements`() {
        // When both fail, we surface the attribute rejection — matches the
        // documented priority. (No formal reason; either order is defensible
        // but consistency aids agent recovery logic.)
        val gated = equipmentItem(
            ItemId("HEAVY_BOW"),
            validSlots = setOf(EquipSlot.MAIN_HAND),
            requiredAttributes = mapOf(Attribute.STRENGTH to 12),
            requiredSkills = mapOf("ARCHERY" to 30),
        )
        val items = StubItemLookup(mapOf(gated.id to gated))
        val agents = StubAgentRegistry(mapOf(agent to agentWith(strength = 5)))
        val skills = StubSkillsRegistry.withLevel("ARCHERY" to 0)
        val instance = unequipped(gated.id)
        val service = EquipmentServiceImpl(StubStore(listOf(instance)), items, agents, skills)

        val result = service.equip(agent, instance.instanceId, EquipSlot.MAIN_HAND)

        assertEquals(EquipRejection.INSUFFICIENT_ATTRIBUTES, (result as EquipResult.Rejected).reason)
    }

    @Test
    fun `skill requirement is skipped entirely when the item declares no required skills`() {
        // Mirrors the empty-attribute optimization with a poison skills
        // registry. An item with empty requiredSkills must not consult the
        // skill snapshot at all.
        val gated = equipmentItem(
            ItemId("PLAIN_RING"),
            validSlots = setOf(EquipSlot.RING_RIGHT),
            requiredAttributes = mapOf(Attribute.STRENGTH to 1),  // tiny attr requirement still works
        )
        val items = StubItemLookup(mapOf(gated.id to gated))
        val poisonSkills = object : AgentSkillsRegistry {
            override fun snapshot(agent: AgentId): AgentSkillsSnapshot =
                error("AgentSkillsRegistry.snapshot should not be called")
            override fun addXpIfSlotted(
                agent: AgentId, skill: SkillId, delta: Int,
            ): dev.gvart.genesara.player.AddXpResult = error("not used")
            override fun maybeRecommend(agent: AgentId, skill: SkillId, tick: Long): Int? = error("not used")
            override fun setSlot(agent: AgentId, skill: SkillId, slotIndex: Int): SkillSlotError? = error("not used")
        }
        val instance = unequipped(gated.id)
        val service = EquipmentServiceImpl(StubStore(listOf(instance)), items, agents, poisonSkills)

        val result = service.equip(agent, instance.instanceId, EquipSlot.RING_RIGHT)

        assertIs<EquipResult.Equipped>(result)
    }

    @Test
    fun `equip throws on state corruption when the agent isn't in the registry`() {
        // The "agent owns instances but isn't registered" branch is unreachable
        // in normal flow (admin seed validates the agent first). It's kept as
        // an `error()` so future state-corruption never silently surfaces as
        // INSUFFICIENT_ATTRIBUTES — pin that contract.
        val gated = equipmentItem(
            ItemId("HEAVY_BLADE"),
            validSlots = setOf(EquipSlot.MAIN_HAND),
            requiredAttributes = mapOf(Attribute.STRENGTH to 1),
        )
        val items = StubItemLookup(mapOf(gated.id to gated))
        val emptyAgents = StubAgentRegistry(emptyMap())
        val instance = unequipped(gated.id)
        val service = EquipmentServiceImpl(StubStore(listOf(instance)), items, emptyAgents, skills)

        try {
            service.equip(agent, instance.instanceId, EquipSlot.MAIN_HAND)
            error("expected an IllegalStateException for the missing agent")
        } catch (_: IllegalStateException) {
            // Expected.
        }
    }

    @Test
    fun `requirement check fires before slot-occupancy and two-handed gates`() {
        // An agent who fails the requirement should learn that — not "slot is
        // occupied" — even when the slot would also be occupied. Pin the
        // priority position from the KDoc.
        val gated = equipmentItem(
            ItemId("HEAVY_BLADE"),
            validSlots = setOf(EquipSlot.MAIN_HAND),
            requiredAttributes = mapOf(Attribute.STRENGTH to 12),
        )
        val items = StubItemLookup(mapOf(gated.id to gated))
        val agents = StubAgentRegistry(mapOf(agent to agentWith(strength = 5)))
        val occupant = unequipped(gated.id, slot = EquipSlot.MAIN_HAND)
        val newcomer = unequipped(gated.id)
        val service = EquipmentServiceImpl(StubStore(listOf(occupant, newcomer)), items, agents, skills)

        val result = service.equip(agent, newcomer.instanceId, EquipSlot.MAIN_HAND)

        assertEquals(EquipRejection.INSUFFICIENT_ATTRIBUTES, (result as EquipResult.Rejected).reason)
    }

    // ─────────────────────── Existing tests continue ───────────────────────

    @Test
    fun `unequip returns the cleared instance when the slot is filled`() {
        val instance = unequipped(swordId, slot = EquipSlot.MAIN_HAND)
        val store = StubStore(listOf(instance))
        val service = EquipmentServiceImpl(store, items, agents, skills)

        val result = service.unequip(agent, EquipSlot.MAIN_HAND)

        val unequipped = assertIs<UnequipResult.Unequipped>(result)
        assertEquals(null, unequipped.instance.equippedInSlot)
        assertEquals(emptyMap(), store.equippedFor(agent))
    }

    @Test
    fun `unequip returns SlotEmpty when the slot has no item`() {
        val service = EquipmentServiceImpl(StubStore(), items, agents, skills)

        val result = service.unequip(agent, EquipSlot.MAIN_HAND)

        assertIs<UnequipResult.SlotEmpty>(result)
    }

    @Test
    fun `equippedFor returns the slot map`() {
        val a = unequipped(swordId, slot = EquipSlot.MAIN_HAND)
        val b = unequipped(helmetId, slot = EquipSlot.HELMET)
        val service = EquipmentServiceImpl(StubStore(listOf(a, b)), items, agents, skills)

        val map = service.equippedFor(agent)

        assertEquals(setOf(EquipSlot.MAIN_HAND, EquipSlot.HELMET), map.keys)
    }

    // ─────────────────────── helpers ───────────────────────

    private fun equipmentItem(
        id: ItemId,
        validSlots: Set<EquipSlot>,
        twoHanded: Boolean = false,
        requiredAttributes: Map<Attribute, Int> = emptyMap(),
        requiredSkills: Map<String, Int> = emptyMap(),
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
        requiredAttributes = requiredAttributes,
        // Tests pass `Map<String, Int>` for ergonomics; convert here to match
        // the production `Map<SkillId, Int>` shape on `Item`.
        requiredSkills = requiredSkills.mapKeys { (id, _) -> SkillId(id) },
    )

    private fun agentWith(
        strength: Int = 50,
        dexterity: Int = 50,
        constitution: Int = 50,
        perception: Int = 50,
        intelligence: Int = 50,
        luck: Int = 50,
    ) = Agent(
        id = agent,
        owner = PlayerId(UUID.randomUUID()),
        name = "test-agent",
        attributes = AgentAttributes(strength, dexterity, constitution, perception, intelligence, luck),
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

    private class StubAgentRegistry(private val byId: Map<AgentId, Agent>) : AgentRegistry {
        override fun find(id: AgentId): Agent? = byId[id]
        override fun listForOwner(owner: PlayerId): List<Agent> =
            byId.values.filter { it.owner == owner }
    }

    /**
     * Skill stub. Build with [withLevel] to add concrete `(skill, level)`
     * entries; absent skills read as level 0 (matching the production
     * snapshot semantics for skills the agent has never trained).
     */
    private class StubSkillsRegistry(
        private val levels: Map<SkillId, Int> = emptyMap(),
    ) : AgentSkillsRegistry {

        override fun snapshot(agent: AgentId): AgentSkillsSnapshot = AgentSkillsSnapshot(
            perSkill = levels.mapValues { (id, level) ->
                AgentSkillState(skill = id, xp = 0, level = level, slotIndex = null, recommendCount = 0)
            },
            slotCount = 8,
            slotsFilled = 0,
        )

        override fun addXpIfSlotted(
            agent: AgentId,
            skill: SkillId,
            delta: Int,
        ): dev.gvart.genesara.player.AddXpResult = dev.gvart.genesara.player.AddXpResult.Unslotted

        override fun maybeRecommend(agent: AgentId, skill: SkillId, tick: Long): Int? = null

        override fun setSlot(agent: AgentId, skill: SkillId, slotIndex: Int): SkillSlotError? = null

        companion object {
            fun withLevel(vararg pairs: Pair<String, Int>): StubSkillsRegistry =
                StubSkillsRegistry(pairs.associate { (id, level) -> SkillId(id) to level })
        }
    }
}
