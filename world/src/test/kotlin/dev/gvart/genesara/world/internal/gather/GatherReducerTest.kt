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
import dev.gvart.genesara.world.NodeResources
import dev.gvart.genesara.world.NodeResourceView
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
import dev.gvart.genesara.world.internal.resources.InitialResourceRow
import dev.gvart.genesara.world.internal.resources.NodeResourceCell
import dev.gvart.genesara.world.internal.resources.NodeResourceStore
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

    private val balance = balance(
        spawns = mapOf(Terrain.FOREST to listOf(rule(wood, 1.0, 50..200))),
        staminaCost = 5,
    )
    private val items = StubItemLookup(mapOf(wood to itemFor(wood), stone to itemFor(stone)))

    @Test
    fun `happy path adds yield to inventory, spends stamina, emits ResourceGathered`() {
        val state = stateWith()
        val command = WorldCommand.GatherResource(agent, wood)
        val store = StubResourceStore(initial = mapOf(wood to 100))

        val result = reduceGather(state, command, balance, items, store, tick = 7)

        val (next, event) = assertNotNull(result.getOrNull())
        // Inventory grew by 1.
        assertEquals(1, next.inventoryOf(agent).quantityOf(wood))
        // Stamina spent.
        assertEquals(25, next.bodyOf(agent)!!.stamina)
        // Store decremented.
        assertEquals(99, store.quantity(wood))
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

        val result = reduceGather(state, WorldCommand.GatherResource(agent, wood), balance, items, StubResourceStore(), tick = 1)

        assertEquals(WorldRejection.NotInWorld(agent), result.leftOrNull())
    }

    @Test
    fun `rejects when item is not in the catalog`() {
        val state = stateWith()
        val unknown = ItemId("PHANTOM")
        val emptyCatalog = StubItemLookup(emptyMap())

        val result = reduceGather(state, WorldCommand.GatherResource(agent, unknown), balance, emptyCatalog, StubResourceStore(), tick = 1)

        assertEquals(WorldRejection.UnknownItem(unknown), result.leftOrNull())
    }

    @Test
    fun `rejects when this node has no row for the item — wrong place to look`() {
        val state = stateWith(terrain = Terrain.FOREST)
        // Forest has a rule for WOOD, but this specific node never spawned STONE.
        val store = StubResourceStore(initial = mapOf(wood to 50))

        val result = reduceGather(state, WorldCommand.GatherResource(agent, stone), balance, items, store, tick = 1)

        assertEquals(
            WorldRejection.ResourceNotAvailableHere(agent, nodeId, stone),
            result.leftOrNull(),
        )
    }

    @Test
    fun `rejects with NodeResourceDepleted when row exists but quantity is zero`() {
        val state = stateWith(terrain = Terrain.FOREST)
        // Node had WOOD, mined out.
        val store = StubResourceStore(initial = mapOf(wood to 0), initialMaxima = mapOf(wood to 100))

        val result = reduceGather(state, WorldCommand.GatherResource(agent, wood), balance, items, store, tick = 1)

        assertEquals(
            WorldRejection.NodeResourceDepleted(agent, nodeId, wood),
            result.leftOrNull(),
        )
    }

    @Test
    fun `rejects when stamina is below the gather cost`() {
        val state = stateWith(stamina = 3)
        val store = StubResourceStore(initial = mapOf(wood to 50))

        val result = reduceGather(state, WorldCommand.GatherResource(agent, wood), balance, items, store, tick = 1)

        assertEquals(
            WorldRejection.NotEnoughStamina(agent, required = 5, available = 3),
            result.leftOrNull(),
        )
    }

    @Test
    fun `accepts when stamina equals the gather cost exactly, leaving stamina at zero`() {
        // Boundary: the rejection is `stamina >= cost`, so cost == stamina must succeed.
        val state = stateWith(stamina = 5)
        val store = StubResourceStore(initial = mapOf(wood to 50))

        val result = reduceGather(state, WorldCommand.GatherResource(agent, wood), balance, items, store, tick = 1)

        val (next, _) = assertNotNull(result.getOrNull())
        assertEquals(0, next.bodyOf(agent)!!.stamina)
        assertEquals(1, next.inventoryOf(agent).quantityOf(wood))
    }

    @Test
    fun `gather yield is clamped by the available cell quantity`() {
        // Cell has 1, yield rule wants more — only 1 comes out.
        val state = stateWith()
        val store = StubResourceStore(initial = mapOf(wood to 1), initialMaxima = mapOf(wood to 100))
        val highYield = balance(
            spawns = mapOf(Terrain.FOREST to listOf(rule(wood, 1.0, 50..200))),
            staminaCost = 5,
            yield = 5,
        )

        val result = reduceGather(state, WorldCommand.GatherResource(agent, wood), highYield, items, store, tick = 1)

        val (next, event) = assertNotNull(result.getOrNull())
        assertEquals(1, next.inventoryOf(agent).quantityOf(wood))
        assertEquals(0, store.quantity(wood))
        val gathered = assertIs<WorldEvent.ResourceGathered>(event)
        assertEquals(1, gathered.quantity)
    }

    private fun rule(item: ItemId, chance: Double, qty: IntRange) =
        ResourceSpawnRule(item = item, spawnChance = chance, quantityRange = qty)

    private fun balance(
        spawns: Map<Terrain, List<ResourceSpawnRule>>,
        staminaCost: Int,
        yield: Int = 1,
    ) = object : BalanceLookup {
        override fun moveStaminaCost(biome: Biome, climate: Climate, terrain: Terrain) = 1
        override fun staminaRegenPerTick(climate: Climate) = 0
        override fun resourceSpawnsFor(terrain: Terrain): List<ResourceSpawnRule> = spawns[terrain].orEmpty()
        override fun gatherStaminaCost(item: ItemId): Int = staminaCost
        override fun gatherYield(item: ItemId): Int = yield
        override fun gaugeDrainPerTick(gauge: dev.gvart.genesara.world.Gauge): Int = 0
        override fun gaugeLowThreshold(gauge: dev.gvart.genesara.world.Gauge): Int = 25
        override fun starvationDamagePerTick(): Int = 0
        override fun isWaterSource(terrain: Terrain): Boolean = false
        override fun drinkStaminaCost(): Int = 1
        override fun drinkThirstRefill(): Int = 25
        override fun sleepRegenPerOfflineTick(): Int = 0
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

    /**
     * In-memory resource store for reducer tests. Pre-seed with `(item → currentQuantity)`;
     * decrement updates the in-memory map in lock-step with what the reducer would persist.
     */
    private inner class StubResourceStore(
        initial: Map<ItemId, Int> = emptyMap(),
        initialMaxima: Map<ItemId, Int> = emptyMap(),
    ) : NodeResourceStore {
        private val cells = initial.mapValues { (item, qty) ->
            qty to (initialMaxima[item] ?: qty.coerceAtLeast(1))
        }.toMutableMap()

        fun quantity(item: ItemId): Int = cells[item]?.first ?: 0

        override fun read(nodeId: NodeId, tick: Long): NodeResources =
            NodeResources(
                cells.mapValues { (item, qty) ->
                    NodeResourceView(itemId = item, quantity = qty.first, initialQuantity = qty.second)
                },
            )

        override fun availability(nodeId: NodeId, item: ItemId, tick: Long): NodeResourceCell? {
            val (qty, initial) = cells[item] ?: return null
            return NodeResourceCell(nodeId, item, qty, initial)
        }

        override fun decrement(nodeId: NodeId, item: ItemId, amount: Int, tick: Long) {
            val (qty, initial) = cells[item] ?: error("decrement on missing cell ($nodeId, $item)")
            check(qty >= amount) { "decrement under zero: have=$qty want=$amount" }
            cells[item] = (qty - amount) to initial
        }

        override fun seed(rows: Collection<InitialResourceRow>, tick: Long) {
            for (row in rows) {
                cells.putIfAbsent(row.item, row.quantity to row.quantity)
            }
        }
    }
}
