package dev.gvart.genesara.world.internal.consume

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.Biome
import dev.gvart.genesara.world.Climate
import dev.gvart.genesara.world.ConsumableEffect
import dev.gvart.genesara.world.Gauge
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
import dev.gvart.genesara.world.internal.body.AgentBody
import dev.gvart.genesara.world.internal.inventory.AgentInventory
import dev.gvart.genesara.world.internal.worldstate.WorldState
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class ConsumeReducerTest {

    private val agent = AgentId(UUID.randomUUID())
    private val regionId = RegionId(1L)
    private val nodeId = NodeId(1L)
    private val berry = ItemId("BERRY")
    private val wood = ItemId("WOOD")

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
        positioned: Boolean = true,
        hunger: Int = 50,
        inventory: AgentInventory = AgentInventory(mapOf(berry to 3)),
    ) = WorldState(
        regions = mapOf(regionId to region),
        nodes = mapOf(nodeId to Node(nodeId, regionId, q = 0, r = 0, terrain = Terrain.PLAINS, adjacency = emptySet())),
        positions = if (positioned) mapOf(agent to nodeId) else emptyMap(),
        bodies = mapOf(agent to AgentBody(
            hp = 50, maxHp = 50,
            stamina = 30, maxStamina = 50,
            mana = 0, maxMana = 0,
            hunger = hunger, maxHunger = 100,
            thirst = 60, maxThirst = 100,
            sleep = 60, maxSleep = 100,
        )),
        inventories = mapOf(agent to inventory),
    )

    private val items = StubItemLookup(
        mapOf(
            berry to item(berry, ConsumableEffect(Gauge.HUNGER, 20)),
            wood to item(wood, null),
        )
    )

    @Test
    fun `happy path - refills the gauge clamped to max, removes 1 from inventory, emits ItemConsumed`() {
        val state = stateWith(hunger = 90, inventory = AgentInventory(mapOf(berry to 2)))
        val command = WorldCommand.ConsumeItem(agent, berry)

        val result = reduceConsume(state, command, items, tick = 7)

        val (next, event) = assertNotNull(result.getOrNull())
        // Gauge clamped at max — agent had 90/100, refill 20 → 100, actual refilled = 10.
        assertEquals(100, next.bodyOf(agent)!!.hunger)
        assertEquals(1, next.inventoryOf(agent).quantityOf(berry))
        val consumed = assertIs<WorldEvent.ItemConsumed>(event)
        assertEquals(agent, consumed.agent)
        assertEquals(berry, consumed.item)
        assertEquals(Gauge.HUNGER, consumed.gauge)
        assertEquals(10, consumed.refilled) // not the configured amount, but the actually-applied delta
        assertEquals(7L, consumed.tick)
        assertEquals(command.commandId, consumed.causedBy)
    }

    @Test
    fun `last unit - removing 1 from a stack of 1 drops the entry entirely`() {
        val state = stateWith(inventory = AgentInventory(mapOf(berry to 1)))

        val result = reduceConsume(state, WorldCommand.ConsumeItem(agent, berry), items, tick = 1)

        val (next, _) = assertNotNull(result.getOrNull())
        assertEquals(0, next.inventoryOf(agent).quantityOf(berry))
    }

    @Test
    fun `rejects when agent is not in the world`() {
        val state = stateWith(positioned = false)

        val result = reduceConsume(state, WorldCommand.ConsumeItem(agent, berry), items, tick = 1)

        assertEquals(WorldRejection.NotInWorld(agent), result.leftOrNull())
    }

    @Test
    fun `rejects when item is not in the catalog`() {
        val state = stateWith()
        val unknown = ItemId("PHANTOM")

        val result = reduceConsume(state, WorldCommand.ConsumeItem(agent, unknown), items, tick = 1)

        assertEquals(WorldRejection.UnknownItem(unknown), result.leftOrNull())
    }

    @Test
    fun `rejects when item is not consumable`() {
        val state = stateWith(inventory = AgentInventory(mapOf(wood to 2)))

        val result = reduceConsume(state, WorldCommand.ConsumeItem(agent, wood), items, tick = 1)

        assertEquals(WorldRejection.ItemNotConsumable(wood), result.leftOrNull())
    }

    @Test
    fun `rejects when agent does not own the item`() {
        val state = stateWith(inventory = AgentInventory.EMPTY)

        val result = reduceConsume(state, WorldCommand.ConsumeItem(agent, berry), items, tick = 1)

        assertEquals(WorldRejection.ItemNotInInventory(agent, berry), result.leftOrNull())
    }

    @Test
    fun `consumability check wins over ownership when both fail simultaneously`() {
        // Agent doesn't own WOOD AND WOOD isn't consumable. Documented priority is:
        // UnknownItem → ItemNotConsumable → ItemNotInInventory, so ItemNotConsumable wins.
        val state = stateWith(inventory = AgentInventory.EMPTY)

        val result = reduceConsume(state, WorldCommand.ConsumeItem(agent, wood), items, tick = 1)

        assertEquals(WorldRejection.ItemNotConsumable(wood), result.leftOrNull())
    }

    private fun item(id: ItemId, effect: ConsumableEffect?) = Item(
        id = id,
        displayName = id.value,
        description = "",
        category = ItemCategory.RESOURCE,
        weightPerUnit = 100,
        maxStack = 100,
        consumable = effect,
    )

    private class StubItemLookup(private val byId: Map<ItemId, Item>) : ItemLookup {
        override fun byId(id: ItemId): Item? = byId[id]
        override fun all(): List<Item> = byId.values.toList()
    }
}
