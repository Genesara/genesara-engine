package dev.gvart.genesara.world.internal.drink

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.Biome
import dev.gvart.genesara.world.Climate
import dev.gvart.genesara.world.Gauge
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.Node
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.Region
import dev.gvart.genesara.world.RegionId
import dev.gvart.genesara.world.Terrain
import dev.gvart.genesara.world.Vec3
import dev.gvart.genesara.world.WorldId
import dev.gvart.genesara.world.Building
import dev.gvart.genesara.world.BuildingCategoryHint
import dev.gvart.genesara.world.BuildingStatus
import dev.gvart.genesara.world.BuildingType
import dev.gvart.genesara.world.BuildingsLookup
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

class DrinkReducerTest {

    private val agent = AgentId(UUID.randomUUID())
    private val regionId = RegionId(1L)
    private val nodeId = NodeId(1L)

    private val region = Region(
        id = regionId,
        worldId = WorldId(1L),
        sphereIndex = 0,
        biome = Biome.COASTAL,
        climate = Climate.OCEANIC,
        centroid = Vec3(0.0, 0.0, 1.0),
        faceVertices = emptyList(),
        neighbors = emptySet(),
    )

    private fun stateWith(
        terrain: Terrain = Terrain.RIVER_DELTA,
        positioned: Boolean = true,
        stamina: Int = 30,
        thirst: Int = 50,
        maxThirst: Int = 100,
    ): WorldState = WorldState(
        regions = mapOf(regionId to region),
        nodes = mapOf(nodeId to Node(nodeId, regionId, q = 0, r = 0, terrain = terrain, adjacency = emptySet())),
        positions = if (positioned) mapOf(agent to nodeId) else emptyMap(),
        bodies = mapOf(
            agent to AgentBody(
                hp = 50, maxHp = 50,
                stamina = stamina, maxStamina = 50,
                mana = 0, maxMana = 0,
                thirst = thirst, maxThirst = maxThirst,
            ),
        ),
        inventories = emptyMap(),
    )

    private val balance = balance(
        waterSources = setOf(Terrain.RIVER_DELTA, Terrain.COASTAL, Terrain.WETLANDS, Terrain.SHORELINE),
        staminaCost = 1,
        thirstRefill = 25,
    )

    @Test
    fun `happy path refills thirst, spends stamina, emits AgentDrank`() {
        val state = stateWith(terrain = Terrain.RIVER_DELTA, thirst = 50)
        val command = WorldCommand.Drink(agent)

        val result = reduceDrink(state, command, balance, NoBuildings, tick = 9)

        val (next, event) = assertNotNull(result.getOrNull())
        val nextBody = next.bodyOf(agent)!!
        assertEquals(75, nextBody.thirst)
        assertEquals(29, nextBody.stamina)
        val drank = assertIs<WorldEvent.AgentDrank>(event)
        assertEquals(agent, drank.agent)
        assertEquals(nodeId, drank.at)
        assertEquals(25, drank.refilled)
        assertEquals(9L, drank.tick)
        assertEquals(command.commandId, drank.causedBy)
    }

    @Test
    fun `rejects when agent is not in the world`() {
        val state = stateWith(positioned = false)

        val result = reduceDrink(state, WorldCommand.Drink(agent), balance, NoBuildings, tick = 1)

        assertEquals(WorldRejection.NotInWorld(agent), result.leftOrNull())
    }

    @Test
    fun `rejects on a non-water-source terrain`() {
        val state = stateWith(terrain = Terrain.FOREST)

        val result = reduceDrink(state, WorldCommand.Drink(agent), balance, NoBuildings, tick = 1)

        assertEquals(WorldRejection.NotAWaterSource(agent, nodeId), result.leftOrNull())
    }

    @Test
    fun `rejects when stamina is below the drink cost`() {
        val state = stateWith(stamina = 0)

        val result = reduceDrink(state, WorldCommand.Drink(agent), balance, NoBuildings, tick = 1)

        assertEquals(
            WorldRejection.NotEnoughStamina(agent, required = 1, available = 0),
            result.leftOrNull(),
        )
    }

    @Test
    fun `clamps refill at max thirst — drinking already full still emits with refilled=0`() {
        val state = stateWith(thirst = 100, maxThirst = 100)

        val result = reduceDrink(state, WorldCommand.Drink(agent), balance, NoBuildings, tick = 3)

        val (next, event) = assertNotNull(result.getOrNull())
        assertEquals(100, next.bodyOf(agent)!!.thirst)
        assertEquals(29, next.bodyOf(agent)!!.stamina)
        val drank = assertIs<WorldEvent.AgentDrank>(event)
        assertEquals(0, drank.refilled)
    }

    @Test
    fun `partial refill clamped to max emits the actual delta`() {
        val state = stateWith(thirst = 90, maxThirst = 100)

        val result = reduceDrink(state, WorldCommand.Drink(agent), balance, NoBuildings, tick = 4)

        val (next, event) = assertNotNull(result.getOrNull())
        assertEquals(100, next.bodyOf(agent)!!.thirst)
        val drank = assertIs<WorldEvent.AgentDrank>(event)
        assertEquals(10, drank.refilled)
    }

    @Test
    fun `every default water-source terrain accepts drink`() {
        listOf(Terrain.COASTAL, Terrain.RIVER_DELTA, Terrain.WETLANDS, Terrain.SHORELINE).forEach { terrain ->
            val state = stateWith(terrain = terrain, thirst = 50)
            val result = reduceDrink(state, WorldCommand.Drink(agent), balance, NoBuildings, tick = 1)
            assertNotNull(result.getOrNull(), "expected $terrain to be a water source")
        }
    }

    @Test
    fun `drink succeeds on a non-water-source terrain when an active well is on the node`() {
        val state = stateWith(terrain = Terrain.FOREST, thirst = 50)
        val well = activeWell(nodeId)
        val buildings = StubBuildingsLookup(byNode = mapOf(nodeId to listOf(well)))

        val result = reduceDrink(state, WorldCommand.Drink(agent), balance, buildings, tick = 1)

        val (next, _) = assertNotNull(result.getOrNull())
        assertEquals(75, next.bodyOf(agent)!!.thirst)
    }

    @Test
    fun `drink rejects on a non-water-source terrain when only an UNDER_CONSTRUCTION well is present`() {
        val state = stateWith(terrain = Terrain.FOREST, thirst = 50)
        val unfinished = activeWell(nodeId).copy(
            status = BuildingStatus.UNDER_CONSTRUCTION,
            progressSteps = 4,
            totalSteps = 14,
        )
        val buildings = StubBuildingsLookup(byNode = mapOf(nodeId to listOf(unfinished)))

        val result = reduceDrink(state, WorldCommand.Drink(agent), balance, buildings, tick = 1)

        assertEquals(WorldRejection.NotAWaterSource(agent, nodeId), result.leftOrNull())
    }

    private fun activeWell(node: NodeId): Building = Building(
        instanceId = UUID.randomUUID(),
        nodeId = node,
        type = BuildingType.WELL,
        status = BuildingStatus.ACTIVE,
        builtByAgentId = agent,
        builtAtTick = 1L,
        lastProgressTick = 1L,
        progressSteps = 14,
        totalSteps = 14,
        hpCurrent = 70,
        hpMax = 70,
    )

    private object NoBuildings : BuildingsLookup {
        override fun byId(id: UUID): Building? = null
        override fun byNode(node: NodeId): List<Building> = emptyList()
        override fun byNodes(nodes: Set<NodeId>): Map<NodeId, List<Building>> = emptyMap()
        override fun activeStationsAt(node: NodeId, hint: BuildingCategoryHint): List<Building> = emptyList()
    }

    private class StubBuildingsLookup(
        private val byNode: Map<NodeId, List<Building>>,
    ) : BuildingsLookup {
        override fun byId(id: UUID): Building? = byNode.values.flatten().firstOrNull { it.instanceId == id }
        override fun byNode(node: NodeId): List<Building> = byNode[node].orEmpty()
        override fun byNodes(nodes: Set<NodeId>): Map<NodeId, List<Building>> =
            nodes.associateWith { byNode[it].orEmpty() }.filterValues { it.isNotEmpty() }
        override fun activeStationsAt(node: NodeId, hint: BuildingCategoryHint): List<Building> =
            byNode[node].orEmpty().filter { it.status == BuildingStatus.ACTIVE }
    }

    private fun balance(
        waterSources: Set<Terrain>,
        staminaCost: Int,
        thirstRefill: Int,
    ) = object : BalanceLookup {
        override fun moveStaminaCost(biome: Biome, climate: Climate, terrain: Terrain) = 1
        override fun staminaRegenPerTick(climate: Climate) = 0
        override fun resourceSpawnsFor(terrain: Terrain): List<dev.gvart.genesara.world.ResourceSpawnRule> = emptyList()
        override fun gatherStaminaCost(item: ItemId): Int = 5
        override fun gatherYield(item: ItemId): Int = 1
        override fun gaugeDrainPerTick(gauge: Gauge): Int = 0
        override fun gaugeLowThreshold(gauge: Gauge): Int = 25
        override fun starvationDamagePerTick(): Int = 0
        override fun isWaterSource(terrain: Terrain): Boolean = terrain in waterSources
        override fun drinkStaminaCost(): Int = staminaCost
        override fun drinkThirstRefill(): Int = thirstRefill
        override fun sleepRegenPerOfflineTick(): Int = 0
        override fun isTraversable(terrain: Terrain): Boolean = true
    }
}
