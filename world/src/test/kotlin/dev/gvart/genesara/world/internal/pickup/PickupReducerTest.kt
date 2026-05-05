package dev.gvart.genesara.world.internal.pickup

import dev.gvart.genesara.account.PlayerId
import dev.gvart.genesara.player.Agent
import dev.gvart.genesara.player.AgentAttributes
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.world.Biome
import dev.gvart.genesara.world.Climate
import dev.gvart.genesara.world.DroppedItemView
import dev.gvart.genesara.world.EquipSlot
import dev.gvart.genesara.world.EquipmentInstance
import dev.gvart.genesara.world.EquipmentInstanceStore
import dev.gvart.genesara.world.Gauge
import dev.gvart.genesara.world.GroundItemStore
import dev.gvart.genesara.world.GroundItemView
import dev.gvart.genesara.world.Item
import dev.gvart.genesara.world.ItemCategory
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.ItemLookup
import dev.gvart.genesara.world.Node
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.Rarity
import dev.gvart.genesara.world.Region
import dev.gvart.genesara.world.RegionId
import dev.gvart.genesara.world.ResourceSpawnRule
import dev.gvart.genesara.world.Terrain
import dev.gvart.genesara.world.Vec3
import dev.gvart.genesara.world.WorldId
import dev.gvart.genesara.world.WorldRejection
import dev.gvart.genesara.world.commands.WorldCommand
import dev.gvart.genesara.world.events.WorldEvent
import dev.gvart.genesara.world.internal.balance.BalanceLookup
import dev.gvart.genesara.world.internal.body.AgentBody
import dev.gvart.genesara.world.internal.inventory.AgentInventory
import dev.gvart.genesara.world.internal.worldstate.WorldState
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PickupReducerTest {

    private val agent = AgentId(UUID.randomUUID())
    private val regionId = RegionId(1L)
    private val nodeId = NodeId(1L)
    private val otherNodeId = NodeId(2L)
    private val wood = ItemId("WOOD")

    private val region = Region(
        id = regionId,
        worldId = WorldId(1L),
        sphereIndex = 0,
        biome = Biome.PLAINS,
        climate = Climate.CONTINENTAL,
        centroid = Vec3(0.0, 0.0, 1.0),
        faceVertices = emptyList(),
        neighbors = emptySet(),
    )
    private val node = Node(nodeId, regionId, q = 0, r = 0, terrain = Terrain.PLAINS, adjacency = emptySet())

    @Test
    fun `agent not positioned — rejects with NotInWorld`() {
        val state = stateWith(positioned = false)
        val command = WorldCommand.Pickup(agent, dropId = UUID.randomUUID())
        val groundItems = StubGroundItemStore()

        val result = reducePickup(
            state, command, balance(), StubItemLookup(), StubAgentRegistry(),
            StubEquipmentStore(), groundItems, tick = 1L,
        )

        assertEquals(WorldRejection.NotInWorld(agent), result.leftOrNull())
        assertEquals(emptyList(), groundItems.taken)
    }

    @Test
    fun `dropId not at the agent's node — rejects with GroundItemNoLongerAvailable`() {
        val state = stateWith()
        val staleId = UUID.randomUUID()
        val groundItems = StubGroundItemStore(atNode = mapOf(otherNodeId to listOf(stackableDrop(staleId, wood, 5))))

        val result = reducePickup(
            state, WorldCommand.Pickup(agent, dropId = staleId), balance(),
            StubItemLookup(woodWeight = 100), StubAgentRegistry(strength = 100),
            StubEquipmentStore(), groundItems, tick = 1L,
        )

        assertEquals(WorldRejection.GroundItemNoLongerAvailable(agent, staleId), result.leftOrNull())
        assertEquals(emptyList(), groundItems.taken, "no take call when atNode lookup misses")
    }

    @Test
    fun `stackable pickup — inventory grows, drop is taken, ItemPickedUp event emitted`() {
        val dropId = UUID.fromString("00000000-0000-0000-0000-0000000000ee")
        val state = stateWith()
        val groundItems = StubGroundItemStore(
            atNode = mapOf(nodeId to listOf(stackableDrop(dropId, wood, 7))),
            takeable = mapOf((nodeId to dropId) to stackableView(dropId, wood, 7)),
        )

        val result = reducePickup(
            state, WorldCommand.Pickup(agent, dropId), balance(),
            StubItemLookup(woodWeight = 100), StubAgentRegistry(strength = 100),
            StubEquipmentStore(), groundItems, tick = 9L,
        )

        val (next, event) = assertNotNull(result.getOrNull())
        assertEquals(7, next.inventoryOf(agent).quantityOf(wood))
        val pickedUp = assertIs<WorldEvent.ItemPickedUp>(event)
        assertEquals(agent, pickedUp.agent)
        assertEquals(nodeId, pickedUp.at)
        val drop = assertIs<DroppedItemView.Stackable>(pickedUp.drop)
        assertEquals(wood, drop.item)
        assertEquals(7, drop.quantity)
        assertEquals(listOf(nodeId to dropId), groundItems.taken)
    }

    @Test
    fun `stackable pickup that would exceed maxStack — rejects with StackFull`() {
        // 99 WOOD already + dropped 5 → would land at 104, breaching maxStack=100.
        val dropId = UUID.randomUUID()
        val state = stateWith().copy(
            inventories = mapOf(agent to AgentInventory(mapOf(wood to 99))),
        )
        val groundItems = StubGroundItemStore(
            atNode = mapOf(nodeId to listOf(stackableDrop(dropId, wood, 5))),
        )

        val result = reducePickup(
            state, WorldCommand.Pickup(agent, dropId), balance(),
            StubItemLookup(woodWeight = 1), StubAgentRegistry(strength = 100_000),
            StubEquipmentStore(), groundItems, tick = 1L,
        )

        assertEquals(
            WorldRejection.StackFull(agent, wood, current = 99, incoming = 5, maxStack = 100),
            result.leftOrNull(),
        )
        assertEquals(emptyList(), groundItems.taken, "rejection happens BEFORE take so drop stays available")
    }

    @Test
    fun `stackable pickup over carry cap — rejects, drop is not taken`() {
        val dropId = UUID.randomUUID()
        // 50 wood × 100g = 5000g ; strength 1 × 100g/str = 100g capacity.
        val state = stateWith()
        val groundItems = StubGroundItemStore(
            atNode = mapOf(nodeId to listOf(stackableDrop(dropId, wood, 50))),
        )

        val result = reducePickup(
            state, WorldCommand.Pickup(agent, dropId),
            balance(carryGramsPerStrengthPoint = 100),
            StubItemLookup(woodWeight = 100), StubAgentRegistry(strength = 1),
            StubEquipmentStore(), groundItems, tick = 1L,
        )

        assertIs<WorldRejection.OverEncumbered>(result.leftOrNull())
        assertEquals(emptyList(), groundItems.taken, "rejection happens BEFORE take so drop stays available")
    }

    @Test
    fun `equipment pickup — instance re-INSERTed under new owner with slot null`() {
        val dropId = UUID.fromString("00000000-0000-0000-0000-0000000000bb")
        val originalInstanceId = UUID.fromString("00000000-0000-0000-0000-0000000000cc")
        val drop = DroppedItemView.Equipment(
            dropId = dropId,
            item = ItemId("IRON_SWORD"),
            instanceId = originalInstanceId,
            rarity = Rarity.RARE,
            durabilityCurrent = 80,
            durabilityMax = 100,
            creatorAgentId = null,
            createdAtTick = 5L,
        )
        val state = stateWith()
        val view = GroundItemView(nodeId = nodeId, droppedAtTick = 5L, drop = drop)
        val groundItems = StubGroundItemStore(
            atNode = mapOf(nodeId to listOf(view)),
            takeable = mapOf((nodeId to dropId) to view),
        )
        val equipment = StubEquipmentStore()

        val result = reducePickup(
            state, WorldCommand.Pickup(agent, dropId), balance(),
            StubItemLookup(), StubAgentRegistry(strength = 100), equipment, groundItems, tick = 9L,
        )

        val (_, event) = assertNotNull(result.getOrNull())
        assertIs<WorldEvent.ItemPickedUp>(event)
        val inserted = assertNotNull(equipment.inserted, "pickup must re-INSERT the instance under the new owner")
        assertEquals(originalInstanceId, inserted.instanceId, "instance id is preserved across drop+pickup")
        assertEquals(agent, inserted.agentId)
        assertEquals(Rarity.RARE, inserted.rarity)
        assertEquals(80, inserted.durabilityCurrent)
        assertNull(inserted.equippedInSlot, "picked-up equipment lands unequipped — agent must call equip separately")
    }

    @Test
    fun `take race lost — second-place agent receives GroundItemNoLongerAvailable`() {
        // Simulates: atNode shows the drop, but between our check and our take call
        // another agent's pickup stole it. take returns null, reducer rejects.
        val dropId = UUID.randomUUID()
        val state = stateWith()
        val groundItems = StubGroundItemStore(
            atNode = mapOf(nodeId to listOf(stackableDrop(dropId, wood, 5))),
            takeable = emptyMap(), // take always returns null — race lost
        )

        val result = reducePickup(
            state, WorldCommand.Pickup(agent, dropId), balance(),
            StubItemLookup(woodWeight = 100), StubAgentRegistry(strength = 100),
            StubEquipmentStore(), groundItems, tick = 1L,
        )

        assertEquals(WorldRejection.GroundItemNoLongerAvailable(agent, dropId), result.leftOrNull())
        assertEquals(listOf(nodeId to dropId), groundItems.taken, "take was attempted, returned null")
    }

    @Test
    fun `stackable drop with unknown item — rejects with UnknownItem`() {
        val dropId = UUID.randomUUID()
        val unknown = ItemId("MYSTERY")
        val state = stateWith()
        val groundItems = StubGroundItemStore(
            atNode = mapOf(nodeId to listOf(stackableDrop(dropId, unknown, 1))),
        )

        val result = reducePickup(
            state, WorldCommand.Pickup(agent, dropId), balance(),
            StubItemLookup(), StubAgentRegistry(strength = 100),
            StubEquipmentStore(), groundItems, tick = 1L,
        )

        assertEquals(WorldRejection.UnknownItem(unknown), result.leftOrNull())
        assertEquals(emptyList(), groundItems.taken)
    }

    private fun stateWith(positioned: Boolean = true): WorldState = WorldState(
        regions = mapOf(regionId to region),
        nodes = mapOf(nodeId to node),
        positions = if (positioned) mapOf(agent to nodeId) else emptyMap(),
        bodies = mapOf(agent to AgentBody(hp = 50, maxHp = 50, stamina = 50, maxStamina = 50, mana = 0, maxMana = 0)),
        inventories = mapOf(agent to AgentInventory.EMPTY),
    )

    private fun stackableDrop(dropId: UUID, item: ItemId, quantity: Int): GroundItemView =
        GroundItemView(
            nodeId = nodeId,
            droppedAtTick = 1L,
            drop = DroppedItemView.Stackable(dropId = dropId, item = item, quantity = quantity),
        )

    private fun stackableView(dropId: UUID, item: ItemId, quantity: Int): GroundItemView =
        stackableDrop(dropId, item, quantity)

    private fun balance(
        carryGramsPerStrengthPoint: Int = 5_000,
    ): BalanceLookup = object : BalanceLookup {
        override fun moveStaminaCost(biome: Biome, climate: Climate, terrain: Terrain) = 1
        override fun staminaRegenPerTick(climate: Climate) = 0
        override fun resourceSpawnsFor(terrain: Terrain): List<ResourceSpawnRule> = emptyList()
        override fun harvestStaminaCost(item: ItemId): Int = 5
        override fun harvestYield(item: ItemId): Int = 1
        override fun gaugeDrainPerTick(gauge: Gauge): Int = 0
        override fun gaugeLowThreshold(gauge: Gauge): Int = 25
        override fun starvationDamagePerTick(): Int = 0
        override fun isWaterSource(terrain: Terrain): Boolean = false
        override fun drinkStaminaCost(): Int = 1
        override fun drinkThirstRefill(): Int = 25
        override fun sleepRegenPerOfflineTick(): Int = 0
        override fun isTraversable(terrain: Terrain): Boolean = true
        override fun carryGramsPerStrengthPoint(): Int = carryGramsPerStrengthPoint
    }

    private inner class StubItemLookup(woodWeight: Int = 100) : ItemLookup {
        private val byId = mapOf(
            wood to Item(
                id = wood,
                displayName = "Wood",
                description = "",
                category = ItemCategory.RESOURCE,
                weightPerUnit = woodWeight,
                maxStack = 100,
            ),
        )
        override fun byId(id: ItemId): Item? = byId[id]
        override fun all(): List<Item> = byId.values.toList()
    }

    private inner class StubAgentRegistry(private val strength: Int = 100) : AgentRegistry {
        override fun find(id: AgentId): Agent? = if (id == agent) {
            Agent(id = id, owner = PlayerId(UUID.randomUUID()), name = "test", attributes = AgentAttributes(strength = strength))
        } else {
            null
        }
        override fun listForOwner(owner: PlayerId): List<Agent> = error("not used")
        override fun applyDeathPenalty(agentId: AgentId, xpLossOnDeath: Int) = error("not used")
    }

    private class StubEquipmentStore : EquipmentInstanceStore {
        var inserted: EquipmentInstance? = null
            private set

        override fun insert(instance: EquipmentInstance) {
            inserted = instance
        }
        override fun findById(instanceId: UUID): EquipmentInstance? = error("not used")
        override fun listByAgent(agentId: AgentId): List<EquipmentInstance> = error("not used")
        override fun equippedFor(agentId: AgentId): Map<EquipSlot, EquipmentInstance> = emptyMap()
        override fun assignToSlot(instanceId: UUID, agentId: AgentId, slot: EquipSlot): EquipmentInstance? =
            error("not used")
        override fun clearSlot(agentId: AgentId, slot: EquipSlot): EquipmentInstance? = error("not used")
        override fun decrementDurability(instanceId: UUID, amount: Int): EquipmentInstance? = error("not used")
        override fun delete(instanceId: UUID): Boolean = error("not used")
    }

    private class StubGroundItemStore(
        private val atNode: Map<NodeId, List<GroundItemView>> = emptyMap(),
        private val takeable: Map<Pair<NodeId, UUID>, GroundItemView> = emptyMap(),
    ) : GroundItemStore {
        val taken: MutableList<Pair<NodeId, UUID>> = mutableListOf()

        override fun deposit(node: NodeId, drop: DroppedItemView, droppedAtTick: Long) = error("not used")
        override fun atNode(node: NodeId): List<GroundItemView> = atNode[node] ?: emptyList()
        override fun take(node: NodeId, dropId: UUID): GroundItemView? {
            taken += node to dropId
            return takeable[node to dropId]
        }
    }
}
