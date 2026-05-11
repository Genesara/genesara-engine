package dev.gvart.genesara.api.internal.mcp.tools.loadout

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityRegistry
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.RaceId
import dev.gvart.genesara.world.BodyView
import dev.gvart.genesara.world.EquipSlot
import dev.gvart.genesara.world.EquipmentInstance
import dev.gvart.genesara.world.EquipmentInstanceStore
import dev.gvart.genesara.world.GroundItemView
import dev.gvart.genesara.world.InventoryEntry
import dev.gvart.genesara.world.InventoryView
import dev.gvart.genesara.world.Item
import dev.gvart.genesara.world.ItemCategory
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.ItemLookup
import dev.gvart.genesara.world.Node
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.NodeResources
import dev.gvart.genesara.world.Rarity
import dev.gvart.genesara.world.Region
import dev.gvart.genesara.world.RegionId
import dev.gvart.genesara.world.WorldQueryGateway
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.model.ToolContext
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class GetLoadoutToolTest {

    private val agent = AgentId(UUID.randomUUID())
    private val clock = MutableTestClock(Instant.parse("2026-01-01T00:00:00Z"))
    private val activity = AgentActivityRegistry(clock)
    private val toolContext = ToolContext(emptyMap())

    @BeforeEach fun setUp() = AgentContextHolder.set(agent)
    @AfterEach fun tearDown() = AgentContextHolder.clear()

    @Test
    fun `projects stackable inventory entries with catalog rarity`() {
        val tool = buildTool(
            inventory = InventoryView(
                entries = listOf(
                    InventoryEntry(ItemId("WOOD"), 5),
                    InventoryEntry(ItemId("BERRY"), 12),
                ),
            ),
            items = StubItems(
                mapOf(
                    ItemId("WOOD") to itemFor(ItemId("WOOD"), Rarity.COMMON),
                    ItemId("BERRY") to itemFor(ItemId("BERRY"), Rarity.COMMON),
                ),
            ),
        )

        val res = tool.invoke(toolContext)

        assertEquals(
            listOf(
                InventoryEntryView("WOOD", 5, Rarity.COMMON),
                InventoryEntryView("BERRY", 12, Rarity.COMMON),
            ),
            res.stackable,
        )
    }

    @Test
    fun `entries surface non-COMMON catalog rarity when the catalog declares one`() {
        val tool = buildTool(
            inventory = InventoryView(entries = listOf(InventoryEntry(ItemId("HEIRLOOM_BLADE"), 1))),
            items = StubItems(
                mapOf(ItemId("HEIRLOOM_BLADE") to itemFor(ItemId("HEIRLOOM_BLADE"), Rarity.RARE)),
            ),
        )

        val res = tool.invoke(toolContext)

        assertEquals(Rarity.RARE, res.stackable.single().rarity)
    }

    @Test
    fun `catalog miss falls back to COMMON rather than throwing`() {
        // A stale agent_inventory row whose item is no longer in the YAML catalog
        // shouldn't crash the read path.
        val tool = buildTool(
            inventory = InventoryView(entries = listOf(InventoryEntry(ItemId("MYSTERY"), 3))),
            items = StubItems(emptyMap()),
        )

        val res = tool.invoke(toolContext)

        assertEquals(Rarity.COMMON, res.stackable.single().rarity)
    }

    @Test
    fun `equipment block lists every defined slot, populating the slotted ones`() {
        val sword = sampleInstance(ItemId("RUSTY_SWORD"), slot = EquipSlot.MAIN_HAND)
        val helmet = sampleInstance(ItemId("LEATHER_HELMET"), slot = EquipSlot.HELMET)
        val tool = buildTool(store = StubStore(listOf(sword, helmet)))

        val equipment = tool.invoke(toolContext).equipment

        assertEquals(EquipSlot.entries.size, equipment.slots.size)
        assertEquals(EquipSlot.entries.map { it.name }, equipment.slots.map { it.slotId })

        val mainHand = equipment.slots.first { it.slotId == "MAIN_HAND" }.instance
        assertNotNull(mainHand)
        assertEquals("RUSTY_SWORD", mainHand.itemId)

        val helmetSlot = equipment.slots.first { it.slotId == "HELMET" }.instance
        assertNotNull(helmetSlot)
        assertEquals("LEATHER_HELMET", helmetSlot.itemId)

        val emptySlots = equipment.slots
            .filter { it.slotId !in setOf("MAIN_HAND", "HELMET") }
        emptySlots.forEach { assertNull(it.instance) }
        assertEquals(emptyList(), equipment.stash)
    }

    @Test
    fun `stash collects every instance the agent owns but has not slotted`() {
        val dagger = sampleInstance(ItemId("DAGGER"), slot = null)
        val ring = sampleInstance(ItemId("BRASS_RING"), slot = null)
        val tool = buildTool(store = StubStore(listOf(dagger, ring)))

        val equipment = tool.invoke(toolContext).equipment

        assertEquals(setOf("DAGGER", "BRASS_RING"), equipment.stash.map { it.itemId }.toSet())
        equipment.slots.forEach { assertNull(it.instance) }
    }

    @Test
    fun `equipment instance projection carries instanceId, durability, creator, rarity`() {
        val instanceId = UUID.randomUUID()
        val creator = AgentId(UUID.randomUUID())
        val blade = EquipmentInstance(
            instanceId = instanceId,
            agentId = agent,
            itemId = ItemId("FROST_BLADE"),
            rarity = Rarity.RARE,
            durabilityCurrent = 17,
            durabilityMax = 100,
            creatorAgentId = creator,
            createdAtTick = 1L,
            equippedInSlot = EquipSlot.MAIN_HAND,
        )
        val tool = buildTool(store = StubStore(listOf(blade)))

        val mainHand = tool.invoke(toolContext).equipment.slots
            .first { it.slotId == "MAIN_HAND" }.instance
        assertNotNull(mainHand)
        assertEquals(instanceId.toString(), mainHand.instanceId)
        assertEquals("FROST_BLADE", mainHand.itemId)
        assertEquals(Rarity.RARE, mainHand.rarity)
        assertEquals(17, mainHand.durabilityCurrent)
        assertEquals(100, mainHand.durabilityMax)
        assertEquals(creator.id.toString(), mainHand.creatorAgentId)
    }

    @Test
    fun `two-handed weapon in MAIN_HAND leaves OFF_HAND null`() {
        // Two-handed semantics live in the equip reducer: the OFF_HAND row is empty in the
        // store while a two-handed item occupies MAIN_HAND. The read tool projects this
        // straight through without special-casing.
        val twoHander = sampleInstance(ItemId("GREATAXE"), slot = EquipSlot.MAIN_HAND)
        val tool = buildTool(store = StubStore(listOf(twoHander)))

        val slots = tool.invoke(toolContext).equipment.slots
        assertNotNull(slots.first { it.slotId == "MAIN_HAND" }.instance)
        assertNull(slots.first { it.slotId == "OFF_HAND" }.instance)
    }

    @Test
    fun `empty agent returns no stackables, all-null slots and empty stash`() {
        val tool = buildTool()

        val res = tool.invoke(toolContext)

        assertEquals(emptyList(), res.stackable)
        assertEquals(EquipSlot.entries.size, res.equipment.slots.size)
        res.equipment.slots.forEach { assertNull(it.instance) }
        assertEquals(emptyList(), res.equipment.stash)
    }

    private fun buildTool(
        inventory: InventoryView = InventoryView(emptyList()),
        items: ItemLookup = StubItems(emptyMap()),
        store: EquipmentInstanceStore = StubStore(emptyList()),
    ) = GetLoadoutTool(
        world = StubQuery(inventory),
        items = items,
        store = store,
        activity = activity,
    )

    private fun itemFor(id: ItemId, rarity: Rarity) = Item(
        id = id,
        displayName = id.value,
        description = "",
        category = ItemCategory.RESOURCE,
        weightPerUnit = 100,
        maxStack = 100,
        rarity = rarity,
    )

    private fun sampleInstance(itemId: ItemId, slot: EquipSlot?) = EquipmentInstance(
        instanceId = UUID.randomUUID(),
        agentId = agent,
        itemId = itemId,
        rarity = Rarity.COMMON,
        durabilityCurrent = 50,
        durabilityMax = 50,
        creatorAgentId = null,
        createdAtTick = 1L,
        equippedInSlot = slot,
    )

    private class StubItems(private val byId: Map<ItemId, Item>) : ItemLookup {
        override fun byId(id: ItemId): Item? = byId[id]
        override fun all(): List<Item> = byId.values.toList()
    }

    private class StubStore(private val instances: List<EquipmentInstance>) : EquipmentInstanceStore {
        override fun insert(instance: EquipmentInstance) = error("not used")
        override fun findById(instanceId: UUID): EquipmentInstance? =
            instances.firstOrNull { it.instanceId == instanceId }
        override fun listByAgent(agentId: AgentId): List<EquipmentInstance> =
            instances.filter { it.agentId == agentId }
        override fun equippedFor(agentId: AgentId): Map<EquipSlot, EquipmentInstance> =
            instances.filter { it.equippedInSlot != null }.associateBy { it.equippedInSlot!! }
        override fun assignToSlot(instanceId: UUID, agentId: AgentId, slot: EquipSlot): EquipmentInstance? = null
        override fun clearSlot(agentId: AgentId, slot: EquipSlot): EquipmentInstance? = null
        override fun decrementDurability(instanceId: UUID, amount: Int): EquipmentInstance? = null
        override fun delete(instanceId: UUID): Boolean = false
    }

    private class StubQuery(private val inventory: InventoryView) : WorldQueryGateway {
        override fun locationOf(agent: AgentId): NodeId? = null
        override fun activePositionOf(agent: AgentId): NodeId? = null
        override fun node(id: NodeId): Node? = null
        override fun region(id: RegionId): Region? = null
        override fun nodesWithin(origin: NodeId, radius: Int): Set<NodeId> = emptySet()
        override fun randomSpawnableNode(): NodeId? = null
        override fun starterNodeFor(race: RaceId): NodeId? = null
        override fun bodyOf(agent: AgentId): BodyView? = null
        override fun inventoryOf(agent: AgentId): InventoryView = inventory
        override fun resourcesAt(nodeId: NodeId, tick: Long): NodeResources = NodeResources.EMPTY
        override fun groundItemsAt(nodeId: NodeId): List<GroundItemView> = emptyList()
        override fun currentTickFor(agent: AgentId): Long = 0L
        override fun activeAgentsAtNodes(nodeIds: Set<NodeId>): Map<NodeId, List<AgentId>> = emptyMap()
    }

    private class MutableTestClock(private var now: Instant) : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
    }
}
