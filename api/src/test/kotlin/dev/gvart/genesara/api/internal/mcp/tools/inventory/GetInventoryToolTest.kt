package dev.gvart.genesara.api.internal.mcp.tools.inventory

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityRegistry
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.RaceId
import dev.gvart.genesara.world.BodyView
import dev.gvart.genesara.world.InventoryEntry
import dev.gvart.genesara.world.InventoryView
import dev.gvart.genesara.world.Item
import dev.gvart.genesara.world.ItemCategory
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.ItemLookup
import dev.gvart.genesara.world.Node
import dev.gvart.genesara.world.NodeId
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

class GetInventoryToolTest {

    private val agent = AgentId(UUID.randomUUID())
    private val clock = MutableTestClock(Instant.parse("2026-01-01T00:00:00Z"))
    private val activity = AgentActivityRegistry(clock)
    private val toolContext = ToolContext(emptyMap())

    @BeforeEach fun setUp() = AgentContextHolder.set(agent)
    @AfterEach fun tearDown() = AgentContextHolder.clear()

    @Test
    fun `projects every inventory entry with its catalog rarity`() {
        val view = InventoryView(
            entries = listOf(
                InventoryEntry(ItemId("WOOD"), 5),
                InventoryEntry(ItemId("BERRY"), 12),
            ),
        )
        val items = StubItems(
            mapOf(
                ItemId("WOOD") to itemFor(ItemId("WOOD"), Rarity.COMMON),
                ItemId("BERRY") to itemFor(ItemId("BERRY"), Rarity.COMMON),
            ),
        )
        val tool = GetInventoryTool(StubQuery(view), items, activity)

        val res = tool.invoke(GetInventoryRequest(), toolContext)

        assertEquals(
            listOf(
                InventoryEntryView("WOOD", 5, "COMMON"),
                InventoryEntryView("BERRY", 12, "COMMON"),
            ),
            res.entries,
        )
    }

    @Test
    fun `entries surface non-COMMON catalog rarity when the catalog declares one`() {
        // Forward-compatibility: catalog entries can declare non-COMMON rarity for
        // future equipment items. The tool reads it through ItemLookup.
        val view = InventoryView(entries = listOf(InventoryEntry(ItemId("HEIRLOOM_BLADE"), 1)))
        val items = StubItems(
            mapOf(ItemId("HEIRLOOM_BLADE") to itemFor(ItemId("HEIRLOOM_BLADE"), Rarity.RARE)),
        )
        val tool = GetInventoryTool(StubQuery(view), items, activity)

        val res = tool.invoke(GetInventoryRequest(), toolContext)

        assertEquals("RARE", res.entries.single().rarity)
    }

    @Test
    fun `catalog miss falls back to COMMON rather than throwing`() {
        // A stale agent_inventory row whose item is no longer in the YAML catalog
        // (e.g. an item was renamed and the migration missed it) shouldn't crash
        // the read path. Surface the row with COMMON so the agent at least sees
        // something they can react to.
        val view = InventoryView(entries = listOf(InventoryEntry(ItemId("MYSTERY"), 3)))
        val tool = GetInventoryTool(StubQuery(view), StubItems(emptyMap()), activity)

        val res = tool.invoke(GetInventoryRequest(), toolContext)

        assertEquals("COMMON", res.entries.single().rarity)
    }

    @Test
    fun `returns empty entries when the agent has no stacks`() {
        val tool = GetInventoryTool(StubQuery(InventoryView(emptyList())), StubItems(emptyMap()), activity)

        val res = tool.invoke(GetInventoryRequest(), toolContext)

        assertEquals(emptyList(), res.entries)
    }

    private fun itemFor(id: ItemId, rarity: Rarity) = Item(
        id = id,
        displayName = id.value,
        description = "",
        category = ItemCategory.RESOURCE,
        weightPerUnit = 100,
        maxStack = 100,
        rarity = rarity,
    )

    private class StubItems(private val byId: Map<ItemId, Item>) : ItemLookup {
        override fun byId(id: ItemId): Item? = byId[id]
        override fun all(): List<Item> = byId.values.toList()
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
        override fun resourcesAt(nodeId: NodeId, tick: Long): dev.gvart.genesara.world.NodeResources =
            dev.gvart.genesara.world.NodeResources.EMPTY
        override fun groundItemsAt(nodeId: NodeId): List<dev.gvart.genesara.world.GroundItemView> = emptyList()
    }

    private class MutableTestClock(private var now: Instant) : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
    }
}
