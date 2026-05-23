package dev.gvart.genesara.api.internal.rest

import dev.gvart.genesara.account.Player
import dev.gvart.genesara.account.PlayerId
import dev.gvart.genesara.player.Agent
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.player.RaceId
import dev.gvart.genesara.world.AgentItemInstancesStore
import dev.gvart.genesara.world.BodyView
import dev.gvart.genesara.world.EquipSlot
import dev.gvart.genesara.world.InventoryEntry
import dev.gvart.genesara.world.InventoryView
import dev.gvart.genesara.world.Item
import dev.gvart.genesara.world.ItemCategory
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.ItemInstance
import dev.gvart.genesara.world.ItemLookup
import dev.gvart.genesara.world.MountId
import dev.gvart.genesara.world.MountSlot
import dev.gvart.genesara.world.Node
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.NodeResources
import dev.gvart.genesara.world.Rarity
import dev.gvart.genesara.world.Region
import dev.gvart.genesara.world.RegionId
import dev.gvart.genesara.world.WorldQueryGateway
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentInventoryControllerTest {

    private val player = Player(id = PlayerId(UUID.randomUUID()), username = "alice", apiToken = "plr_x")
    private val agent = Agent(id = AgentId(UUID.randomUUID()), owner = player.id, name = "Ada")
    private val creator = AgentId(UUID.randomUUID())

    @Test
    fun `inventory returns one stackable entry per item with the catalog rarity`() {
        val items = StubItemLookup(mapOf(ItemId("oak_log") to Rarity.UNCOMMON))
        val world = StubWorld(
            inventory = InventoryView(listOf(InventoryEntry(ItemId("oak_log"), quantity = 3))),
        )
        val controller = AgentInventoryController(resolver(), world, items, EmptyInstances)

        val response = controller.inventory(player, agent.id.id)

        val entry = response.entries.single()
        assertEquals("oak_log", entry.itemId)
        assertEquals(3, entry.quantity)
        assertEquals(Rarity.UNCOMMON, entry.rarity)
    }

    @Test
    fun `loadout splits equipment into slots versus stash and wire-encodes the creator agent id`() {
        val sword = ItemInstance.Equipment(
            instanceId = UUID.randomUUID(),
            agentId = agent.id,
            itemId = ItemId("iron_sword"),
            rarity = Rarity.RARE,
            durabilityCurrent = 80,
            durabilityMax = 100,
            creatorAgentId = creator,
            createdAtTick = 5L,
            equippedInSlot = EquipSlot.MAIN_HAND,
        )
        val stashHelm = ItemInstance.Equipment(
            instanceId = UUID.randomUUID(),
            agentId = agent.id,
            itemId = ItemId("leather_helm"),
            rarity = Rarity.COMMON,
            durabilityCurrent = 40,
            durabilityMax = 50,
            creatorAgentId = null,
            createdAtTick = 6L,
            equippedInSlot = null,
        )
        val controller = AgentInventoryController(
            resolver(),
            StubWorld(),
            StubItemLookup(emptyMap()),
            StubInstances(listOf(sword, stashHelm)),
        )

        val response = controller.loadout(player, agent.id.id)

        assertTrue(response.stackable.isEmpty())
        val mainHand = response.equipment.slots.single { it.slotId == EquipSlot.MAIN_HAND }
        val mainHandInstance = assertNotNull(mainHand.instance)
        assertEquals("iron_sword", mainHandInstance.itemId)
        assertEquals(ItemCategory.EQUIPMENT, mainHandInstance.category)
        assertEquals("agent:${creator.id}", mainHandInstance.creatorAgentId)

        val stash = response.equipment.stash.single()
        assertEquals("leather_helm", stash.itemId)
        assertEquals(ItemCategory.EQUIPMENT, stash.category)
        assertNull(stash.creatorAgentId, "loot drops without a creator stay null")

        val emptySlots = response.equipment.slots.filter { it.slotId != EquipSlot.MAIN_HAND }
        assertTrue(emptySlots.all { it.instance == null })
    }

    private fun resolver() = OwnedAgentResolver(SingleRegistry(agent))

    private class SingleRegistry(private val agent: Agent) : AgentRegistry {
        override fun find(id: AgentId): Agent? = if (id == agent.id) agent else null
        override fun listForOwner(owner: PlayerId): List<Agent> = emptyList()
    }

    private class StubItemLookup(private val rarities: Map<ItemId, Rarity>) : ItemLookup {
        override fun byId(id: ItemId): Item? =
            rarities[id]?.let { rarity ->
                Item(
                    id = id,
                    displayName = id.value,
                    description = "",
                    category = ItemCategory.RESOURCE,
                    weightPerUnit = 1,
                    maxStack = 100,
                    rarity = rarity,
                )
            }
        override fun all(): List<Item> = emptyList()
    }

    private class StubWorld(
        private val inventory: InventoryView = InventoryView(emptyList()),
    ) : WorldQueryGateway {
        override fun inventoryOf(agent: AgentId): InventoryView = inventory
        override fun locationOf(agent: AgentId): NodeId? = null
        override fun activePositionOf(agent: AgentId): NodeId? = null
        override fun bodyOf(agent: AgentId): BodyView? = null
        override fun node(id: NodeId): Node? = null
        override fun region(id: RegionId): Region? = null
        override fun nodesWithin(origin: NodeId, radius: Int): Set<NodeId> = emptySet()
        override fun randomSpawnableNode(): NodeId? = null
        override fun starterNodeFor(race: RaceId): NodeId? = null
        override fun resourcesAt(nodeId: NodeId, tick: Long): NodeResources = NodeResources.EMPTY
        override fun groundItemsAt(nodeId: NodeId): List<dev.gvart.genesara.world.GroundItemView> = emptyList()
        override fun currentTickFor(agent: AgentId): Long = 0L
        override fun activeAgentsAtNodes(nodeIds: Set<NodeId>): Map<NodeId, List<AgentId>> = emptyMap()
    }

    private object EmptyInstances : AgentItemInstancesStore {
        override fun insert(instance: ItemInstance) {}
        override fun findById(instanceId: UUID): ItemInstance? = null
        override fun listByAgent(agentId: AgentId): List<ItemInstance> = emptyList()
        override fun delete(instanceId: UUID): Boolean = false
        override fun equippedFor(agentId: AgentId): Map<EquipSlot, ItemInstance.Equipment> = emptyMap()
        override fun assignToSlot(instanceId: UUID, agentId: AgentId, slot: EquipSlot): ItemInstance.Equipment? = null
        override fun clearSlot(agentId: AgentId, slot: EquipSlot): ItemInstance.Equipment? = null
        override fun decrementDurability(instanceId: UUID, amount: Int): ItemInstance.Equipment? = null
        override fun agentHoldsKeyFor(agent: AgentId, gateInstanceId: UUID): Boolean = false
        override fun assignToMountSlot(
            instanceId: UUID,
            agentId: AgentId,
            mountId: MountId,
            slot: MountSlot,
        ): ItemInstance.MountGear? = null
        override fun clearMountSlot(mountId: MountId, slot: MountSlot): ItemInstance.MountGear? = null
        override fun byEquippedOnMount(mountId: MountId): List<ItemInstance.MountGear> = emptyList()
        override fun equippedForAll(agents: Set<AgentId>): Map<AgentId, Map<EquipSlot, ItemInstance.Equipment>> = emptyMap()
        override fun stowOnMount(instanceId: UUID, agentId: AgentId, mountId: MountId): ItemInstance? = null
        override fun unstowFromMount(instanceId: UUID): ItemInstance? = null
        override fun byStowedOnMount(mountId: MountId): List<ItemInstance> = emptyList()
        override fun gearOnMount(mountId: MountId, slot: MountSlot): ItemInstance.MountGear? = null
    }

    private class StubInstances(private val rows: List<ItemInstance>) : AgentItemInstancesStore {
        override fun insert(instance: ItemInstance) {}
        override fun findById(instanceId: UUID): ItemInstance? = rows.firstOrNull { it.instanceId == instanceId }
        override fun listByAgent(agentId: AgentId): List<ItemInstance> = rows
        override fun delete(instanceId: UUID): Boolean = false
        override fun equippedFor(agentId: AgentId): Map<EquipSlot, ItemInstance.Equipment> = emptyMap()
        override fun assignToSlot(instanceId: UUID, agentId: AgentId, slot: EquipSlot): ItemInstance.Equipment? = null
        override fun clearSlot(agentId: AgentId, slot: EquipSlot): ItemInstance.Equipment? = null
        override fun decrementDurability(instanceId: UUID, amount: Int): ItemInstance.Equipment? = null
        override fun agentHoldsKeyFor(agent: AgentId, gateInstanceId: UUID): Boolean = false
        override fun assignToMountSlot(
            instanceId: UUID,
            agentId: AgentId,
            mountId: MountId,
            slot: MountSlot,
        ): ItemInstance.MountGear? = null
        override fun clearMountSlot(mountId: MountId, slot: MountSlot): ItemInstance.MountGear? = null
        override fun byEquippedOnMount(mountId: MountId): List<ItemInstance.MountGear> = emptyList()
        override fun equippedForAll(agents: Set<AgentId>): Map<AgentId, Map<EquipSlot, ItemInstance.Equipment>> = emptyMap()
        override fun stowOnMount(instanceId: UUID, agentId: AgentId, mountId: MountId): ItemInstance? = null
        override fun unstowFromMount(instanceId: UUID): ItemInstance? = null
        override fun byStowedOnMount(mountId: MountId): List<ItemInstance> = emptyList()
        override fun gearOnMount(mountId: MountId, slot: MountSlot): ItemInstance.MountGear? = null
    }
}
