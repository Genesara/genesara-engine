package dev.gvart.genesara.world.internal.gather

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.Biome
import dev.gvart.genesara.world.Climate
import dev.gvart.genesara.world.Item
import dev.gvart.genesara.world.ItemCategory
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.ItemLookup
import dev.gvart.genesara.world.Node
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.Region
import dev.gvart.genesara.world.RegionId
import dev.gvart.genesara.world.Terrain
import dev.gvart.genesara.world.Vec3
import dev.gvart.genesara.world.WorldId
import dev.gvart.genesara.world.WorldRejection
import dev.gvart.genesara.world.commands.WorldCommand
import dev.gvart.genesara.world.events.WorldEvent
import dev.gvart.genesara.world.internal.balance.BalanceLookup
import dev.gvart.genesara.world.internal.body.AgentBody
import dev.gvart.genesara.world.internal.worldstate.WorldState
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class GatherReducerTest {

    private val agent = AgentId(UUID.randomUUID())
    private val regionId = RegionId(1L)
    private val nodeId = NodeId(1L)
    private val wood = ItemId("WOOD")
    private val stone = ItemId("STONE")

    private val region = Region(
        id = regionId,
        worldId = WorldId(1L),
        sphereIndex = 0,
        biome = Biome.PLAINS,
        climate = Climate.OCEANIC,
        centroid = Vec3(0.0, 0.0, 1.0),
        faceVertices = emptyList(),
        neighbors = emptySet(),
    )

    private fun stateWith(
        terrain: Terrain = Terrain.FOREST,
        positioned: Boolean = true,
        stamina: Int = 30,
    ): WorldState = WorldState(
        regions = mapOf(regionId to region),
        nodes = mapOf(nodeId to Node(nodeId, regionId, q = 0, r = 0, terrain = terrain, adjacency = emptySet())),
        positions = if (positioned) mapOf(agent to nodeId) else emptyMap(),
        bodies = mapOf(agent to AgentBody(hp = 50, maxHp = 50, stamina = stamina, maxStamina = 50, mana = 0, maxMana = 0)),
        inventories = emptyMap(),
    )

    private val balance = balance(gatherables = mapOf(Terrain.FOREST to listOf(wood)), staminaCost = 5)
    private val items = StubItemLookup(mapOf(wood to itemFor(wood), stone to itemFor(stone)))

    @Test
    fun `happy path adds yield to inventory, spends stamina, emits ResourceGathered`() {
        val state = stateWith()
        val command = WorldCommand.GatherResource(agent, wood)

        val result = reduceGather(state, command, balance, items, tick = 7)

        val (next, event) = assertNotNull(result.getOrNull())
        // Inventory grew by 1.
        assertEquals(1, next.inventoryOf(agent).quantityOf(wood))
        // Stamina spent.
        assertEquals(25, next.bodyOf(agent)!!.stamina)
        // Event matches.
        val gathered = assertIs<WorldEvent.ResourceGathered>(event)
        assertEquals(agent, gathered.agent)
        assertEquals(nodeId, gathered.at)
        assertEquals(wood, gathered.item)
        assertEquals(1, gathered.quantity)
        assertEquals(7L, gathered.tick)
        assertEquals(command.commandId, gathered.causedBy)
    }

    @Test
    fun `rejects when agent is not in the world`() {
        val state = stateWith(positioned = false)

        val result = reduceGather(state, WorldCommand.GatherResource(agent, wood), balance, items, tick = 1)

        assertEquals(WorldRejection.NotInWorld(agent), result.leftOrNull())
    }

    @Test
    fun `rejects when item is not in the catalog`() {
        val state = stateWith()
        val unknown = ItemId("PHANTOM")
        val emptyCatalog = StubItemLookup(emptyMap())

        val result = reduceGather(state, WorldCommand.GatherResource(agent, unknown), balance, emptyCatalog, tick = 1)

        assertEquals(WorldRejection.UnknownItem(unknown), result.leftOrNull())
    }

    @Test
    fun `rejects when terrain does not list the item among gatherables`() {
        val state = stateWith(terrain = Terrain.FOREST)

        // FOREST has WOOD configured, but agent asks for STONE.
        val result = reduceGather(state, WorldCommand.GatherResource(agent, stone), balance, items, tick = 1)

        assertEquals(
            WorldRejection.ResourceNotAvailableHere(agent, nodeId, stone),
            result.leftOrNull(),
        )
    }

    @Test
    fun `rejects when stamina is below the gather cost`() {
        val state = stateWith(stamina = 3)

        val result = reduceGather(state, WorldCommand.GatherResource(agent, wood), balance, items, tick = 1)

        assertEquals(
            WorldRejection.NotEnoughStamina(agent, required = 5, available = 3),
            result.leftOrNull(),
        )
    }

    @Test
    fun `accepts when stamina equals the gather cost exactly, leaving stamina at zero`() {
        // Boundary: the rejection is `stamina >= cost`, so cost == stamina must succeed.
        val state = stateWith(stamina = 5)

        val result = reduceGather(state, WorldCommand.GatherResource(agent, wood), balance, items, tick = 1)

        val (next, _) = assertNotNull(result.getOrNull())
        assertEquals(0, next.bodyOf(agent)!!.stamina)
        assertEquals(1, next.inventoryOf(agent).quantityOf(wood))
    }

    private fun balance(gatherables: Map<Terrain, List<ItemId>>, staminaCost: Int) = object : BalanceLookup {
        override fun moveStaminaCost(biome: Biome, climate: Climate, terrain: Terrain) = 1
        override fun staminaRegenPerTick(climate: Climate) = 0
        override fun gatherablesIn(terrain: Terrain): List<ItemId> = gatherables[terrain].orEmpty()
        override fun gatherStaminaCost(item: ItemId): Int = staminaCost
        override fun gatherYield(item: ItemId): Int = 1
        override fun gaugeDrainPerTick(gauge: dev.gvart.genesara.world.Gauge): Int = 0
        override fun gaugeLowThreshold(gauge: dev.gvart.genesara.world.Gauge): Int = 25
        override fun starvationDamagePerTick(): Int = 0
    }

    private fun itemFor(id: ItemId) = Item(
        id = id,
        displayName = id.value,
        description = "",
        category = ItemCategory.RESOURCE,
        weightPerUnit = 100,
        maxStack = 100,
    )

    private class StubItemLookup(private val byId: Map<ItemId, Item>) : ItemLookup {
        override fun byId(id: ItemId): Item? = byId[id]
        override fun all(): List<Item> = byId.values.toList()
    }
}
