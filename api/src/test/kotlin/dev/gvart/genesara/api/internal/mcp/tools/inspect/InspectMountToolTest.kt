package dev.gvart.genesara.api.internal.mcp.tools.inspect

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityRegistry
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.BodyView
import dev.gvart.genesara.world.GroundItemView
import dev.gvart.genesara.world.InventoryView
import dev.gvart.genesara.world.Item
import dev.gvart.genesara.world.ItemCategory
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.ItemInstance
import dev.gvart.genesara.world.ItemLookup
import dev.gvart.genesara.world.MaintenanceType
import dev.gvart.genesara.world.Mount
import dev.gvart.genesara.world.MountCatalog
import dev.gvart.genesara.world.MountDef
import dev.gvart.genesara.world.MountId
import dev.gvart.genesara.world.MountInstanceStore
import dev.gvart.genesara.world.MountSlot
import dev.gvart.genesara.world.MountType
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.NodeResources
import dev.gvart.genesara.world.Npc
import dev.gvart.genesara.world.NpcDef
import dev.gvart.genesara.world.NpcType
import dev.gvart.genesara.world.Rarity
import dev.gvart.genesara.world.WorldQueryGateway
import dev.gvart.genesara.api.testsupport.InMemoryAgentItemInstancesStore
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
import kotlin.test.assertTrue

class InspectMountToolTest {

    private val agentId = AgentId(UUID.fromString("11111111-1111-1111-1111-111111111111"))
    private val ownerId = AgentId(UUID.fromString("22222222-2222-2222-2222-222222222222"))
    private val nodeId = NodeId(1L)
    private val farNodeId = NodeId(99L)
    private val mountId = MountId(UUID.fromString("33333333-3333-3333-3333-333333333333"))
    private val mount = Mount(
        id = mountId,
        type = MountType("RIDING_HORSE"),
        ownerAgentId = ownerId,
        nodeId = nodeId,
        hpCurrent = 28, hpMax = 30,
        hunger = 60, hungerMax = 100,
        fatigue = 12, fatigueMax = 80,
        mountedByAgentId = null,
        tamedAtTick = 0L,
    )
    private val def = MountDef(
        type = MountType("RIDING_HORSE"),
        displayName = "Riding Horse",
        tamedFrom = NpcType("WILD_HORSE"),
        tameability = 50,
        hpMax = 30, hungerMax = 100, fatigueMax = 80,
        speedFactor = 0.9,
        carryCapacityGrams = 40_000,
        defense = 2,
        gearSlots = setOf(MountSlot.SADDLE, MountSlot.BARDING, MountSlot.HARNESS),
        maintenanceType = MaintenanceType.ANIMAL,
    )
    private val clock = MutableTestClock(Instant.parse("2026-01-01T00:00:00Z"))
    private val activity = AgentActivityRegistry(clock)
    private val toolContext = ToolContext(emptyMap())

    @BeforeEach fun setUp() = AgentContextHolder.set(agentId)
    @AfterEach fun tearDown() = AgentContextHolder.clear()

    @Test
    fun `returns the full mount detail for a visible mount`() {
        val tool = InspectMountTool(
            world = StubWorld(nodeId, setOf(nodeId)),
            mounts = StubMounts(listOf(mount)),
            mountCatalog = StubCatalog(def),
            itemInstances = InMemoryAgentItemInstancesStore(),
            items = EmptyItems,
            activity = activity,
        )

        val response = tool.invoke("mount:${mountId.value}", toolContext)

        assertEquals("mount", response.kind)
        val view = assertNotNull(response.mount)
        assertEquals("mount:${mountId.value}", view.id)
        assertEquals("RIDING_HORSE", view.type)
        assertEquals("Riding Horse", view.displayName)
        assertEquals(nodeId.value, view.nodeId)
        assertEquals(28, view.hpCurrent)
        assertEquals(30, view.hpMax)
        assertEquals(60, view.hunger)
        assertEquals(100, view.hungerMax)
        assertEquals(12, view.fatigue)
        assertEquals(80, view.fatigueMax)
        assertEquals("agent:${ownerId.id}", view.owner)
        assertNull(view.rider)
        assertTrue(view.equipped.isEmpty())
        assertNull(view.cargo)
    }

    @Test
    fun `includes equipped mount gear keyed by slot with the catalog display name`() {
        val saddleId = UUID.randomUUID()
        val items = InMemoryAgentItemInstancesStore()
        items.seed(
            ItemInstance.MountGear(
                instanceId = saddleId,
                agentId = ownerId,
                itemId = ItemId("LEATHER_SADDLE"),
                rarity = Rarity.COMMON,
                durabilityCurrent = 50,
                durabilityMax = 50,
                creatorAgentId = null,
                createdAtTick = 0L,
                equippedOnMount = mountId.value,
                equippedMountSlot = MountSlot.SADDLE,
            ),
        )
        items.seed(
            ItemInstance.MountGear(
                instanceId = UUID.randomUUID(),
                agentId = ownerId,
                itemId = ItemId("STORED_BARDING"),
                rarity = Rarity.COMMON,
                durabilityCurrent = 50,
                durabilityMax = 50,
                creatorAgentId = null,
                createdAtTick = 0L,
                equippedOnMount = null,
                equippedMountSlot = null,
            ),
        )
        val tool = InspectMountTool(
            world = StubWorld(nodeId, setOf(nodeId)),
            mounts = StubMounts(listOf(mount)),
            mountCatalog = StubCatalog(def),
            itemInstances = items,
            items = MapItems(mapOf(ItemId("LEATHER_SADDLE") to "Leather Saddle")),
            activity = activity,
        )

        val view = assertNotNull(tool.invoke("mount:${mountId.value}", toolContext).mount)

        assertEquals(mapOf("SADDLE" to "Leather Saddle"), view.equipped)
    }

    @Test
    fun `rejects a bare UUID with bad_target_id`() {
        val tool = newTool()

        val response = tool.invoke(mountId.value.toString(), toolContext)

        assertEquals("error", response.kind)
        assertEquals("bad_target_id", response.error?.code)
        assertNull(response.mount)
    }

    @Test
    fun `rejects an npc-prefixed UUID with bad_target_id`() {
        val tool = newTool()

        val response = tool.invoke("npc:${mountId.value}", toolContext)

        assertEquals("bad_target_id", response.error?.code)
    }

    @Test
    fun `rejects a malformed UUID after the mount prefix with bad_target_id`() {
        val tool = newTool()

        val response = tool.invoke("mount:not-a-uuid", toolContext)

        assertEquals("bad_target_id", response.error?.code)
    }

    @Test
    fun `returns not_in_world when the calling agent has not spawned`() {
        val tool = InspectMountTool(
            world = StubWorld(location = null, visible = setOf(nodeId)),
            mounts = StubMounts(listOf(mount)),
            mountCatalog = StubCatalog(def),
            itemInstances = InMemoryAgentItemInstancesStore(),
            items = EmptyItems,
            activity = activity,
        )

        val response = tool.invoke("mount:${mountId.value}", toolContext)

        assertEquals("not_in_world", response.error?.code)
    }

    @Test
    fun `returns not_visible when the mount sits on a node outside the visible radius`() {
        val tool = InspectMountTool(
            world = StubWorld(nodeId, setOf(nodeId)),
            mounts = StubMounts(listOf(mount.copy(nodeId = farNodeId))),
            mountCatalog = StubCatalog(def),
            itemInstances = InMemoryAgentItemInstancesStore(),
            items = EmptyItems,
            activity = activity,
        )

        val response = tool.invoke("mount:${mountId.value}", toolContext)

        assertEquals("not_visible", response.error?.code)
        assertNull(response.mount)
    }

    @Test
    fun `falls back to the catalog type when the mount type is not in the catalog`() {
        val tool = InspectMountTool(
            world = StubWorld(nodeId, setOf(nodeId)),
            mounts = StubMounts(listOf(mount)),
            mountCatalog = StubCatalog(null),
            itemInstances = InMemoryAgentItemInstancesStore(),
            items = EmptyItems,
            activity = activity,
        )

        val view = assertNotNull(tool.invoke("mount:${mountId.value}", toolContext).mount)

        assertEquals("RIDING_HORSE", view.displayName)
    }

    private fun newTool() = InspectMountTool(
        world = StubWorld(nodeId, setOf(nodeId)),
        mounts = StubMounts(listOf(mount)),
        mountCatalog = StubCatalog(def),
        itemInstances = InMemoryAgentItemInstancesStore(),
        items = EmptyItems,
        activity = activity,
    )

    private class StubWorld(
        private val location: NodeId?,
        private val visible: Set<NodeId>,
    ) : WorldQueryGateway {
        override fun locationOf(agent: AgentId): NodeId? = location
        override fun activePositionOf(agent: AgentId): NodeId? = location
        override fun node(id: NodeId): dev.gvart.genesara.world.Node? = null
        override fun region(id: dev.gvart.genesara.world.RegionId): dev.gvart.genesara.world.Region? = null
        override fun nodesWithin(origin: NodeId, radius: Int): Set<NodeId> = visible
        override fun randomSpawnableNode(): NodeId? = null
        override fun starterNodeFor(race: dev.gvart.genesara.player.RaceId): NodeId? = null
        override fun bodyOf(agent: AgentId): BodyView? = null
        override fun inventoryOf(agent: AgentId): InventoryView = InventoryView(emptyList())
        override fun resourcesAt(nodeId: NodeId, tick: Long): NodeResources = NodeResources.EMPTY
        override fun groundItemsAt(nodeId: NodeId): List<GroundItemView> = emptyList()
        override fun currentTickFor(agent: AgentId): Long = 0L
        override fun activeAgentsAtNodes(nodeIds: Set<NodeId>): Map<NodeId, List<AgentId>> = emptyMap()
        override fun npcsAtNodes(nodeIds: Set<NodeId>): Map<NodeId, List<Npc>> = emptyMap()
        override fun npcDef(type: NpcType): NpcDef? = null
    }

    private class StubMounts(private val all: List<Mount>) : MountInstanceStore {
        override fun insert(mount: Mount) = error("not used")
        override fun findById(mountId: MountId): Mount? = all.firstOrNull { it.id == mountId }
        override fun byNodes(nodeIds: Collection<NodeId>): List<Mount> = all.filter { it.nodeId in nodeIds }
        override fun byOwner(agentId: AgentId): List<Mount> = all.filter { it.ownerAgentId == agentId }
        override fun findByRider(agentId: AgentId): Mount? = all.firstOrNull { it.mountedByAgentId == agentId }
        override fun all(): List<Mount> = all
        override fun delete(mountId: MountId): Boolean = false
        override fun update(mount: Mount): Boolean = false
    }

    private class StubCatalog(private val def: MountDef?) : MountCatalog {
        override fun byType(type: MountType): MountDef? = def?.takeIf { it.type == type }
        override fun byTamedFromNpc(npcType: NpcType): MountDef? = null
        override fun all(): Collection<MountDef> = listOfNotNull(def)
    }

    private object EmptyItems : ItemLookup {
        override fun byId(id: ItemId): Item? = null
        override fun all(): List<Item> = emptyList()
    }

    private class MapItems(private val displays: Map<ItemId, String>) : ItemLookup {
        override fun byId(id: ItemId): Item? = displays[id]?.let { stubItem(id, it) }
        override fun all(): List<Item> = displays.entries.map { stubItem(it.key, it.value) }
    }

    private class MutableTestClock(private var now: Instant) : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
    }
}

private fun stubItem(id: ItemId, displayName: String): Item =
    Item(
        id = id,
        displayName = displayName,
        description = "",
        category = ItemCategory.EQUIPMENT,
        weightPerUnit = 1_000,
        maxStack = 1,
    )
