package dev.gvart.genesara.world.internal.movement

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.Biome
import dev.gvart.genesara.world.Climate
import dev.gvart.genesara.world.Node
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.Region
import dev.gvart.genesara.world.RegionId
import dev.gvart.genesara.world.Terrain
import dev.gvart.genesara.world.Vec3
import dev.gvart.genesara.world.WorldId
import dev.gvart.genesara.world.Building
import dev.gvart.genesara.world.BuildingCategoryHint
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
import kotlin.test.assertTrue

class MovementReducerTest {

    private val agent = AgentId(UUID.randomUUID())
    private val worldId = WorldId(1L)
    private val region = RegionId(1L)
    private val a = NodeId(1L)
    private val b = NodeId(2L)
    private val c = NodeId(3L)

    private val flatCost = balanceLookup(cost = 1)

    private val world = WorldState(
        regions = mapOf(
            region to Region(
                id = region,
                worldId = worldId,
                sphereIndex = 0,
                biome = Biome.PLAINS,
                climate = Climate.OCEANIC,
                centroid = Vec3(0.0, 0.0, 1.0),
                faceVertices = emptyList(),
                neighbors = emptySet(),
            ),
        ),
        nodes = mapOf(
            a to Node(a, region, q = 0, r = 0, terrain = Terrain.PLAINS, adjacency = setOf(b)),
            b to Node(b, region, q = 1, r = 0, terrain = Terrain.PLAINS, adjacency = setOf(a, c)),
            c to Node(c, region, q = 2, r = 0, terrain = Terrain.PLAINS, adjacency = setOf(b)),
        ),
        positions = mapOf(agent to a),
        bodies = mapOf(agent to body(stamina = 10, maxStamina = 10)),
        inventories = emptyMap(),
    )

    @Test
    fun `accepts move to adjacent node, deducts stamina, and emits AgentMoved`() {
        val command = WorldCommand.MoveAgent(agent, b)
        val result = reduceMove(world, command, flatCost, NoBuildings, tick = 1)

        result.fold(
            ifLeft = { error("expected Right but got $it") },
            ifRight = { (next, event) ->
                assertEquals(b, next.positions[agent])
                assertEquals(9, next.bodyOf(agent)!!.stamina)
                assertEquals(
                    WorldEvent.AgentMoved(agent, a, b, tick = 1, causedBy = command.commandId),
                    event,
                )
            },
        )
    }

    @Test
    fun `rejects move when agent is unknown`() {
        val unknown = AgentId(UUID.randomUUID())
        val result = reduceMove(world, WorldCommand.MoveAgent(unknown, b), flatCost, NoBuildings, tick = 1)

        assertTrue(result.isLeft())
        assertEquals(WorldRejection.UnknownAgent(unknown), result.leftOrNull())
    }

    @Test
    fun `rejects move to unknown node`() {
        val ghost = NodeId(99L)
        val result = reduceMove(world, WorldCommand.MoveAgent(agent, ghost), flatCost, NoBuildings, tick = 1)

        assertEquals(WorldRejection.UnknownNode(ghost), result.leftOrNull())
    }

    @Test
    fun `rejects move to non-adjacent node`() {
        val result = reduceMove(world, WorldCommand.MoveAgent(agent, c), flatCost, NoBuildings, tick = 1)

        assertEquals(WorldRejection.NotAdjacent(a, c), result.leftOrNull())
    }

    @Test
    fun `rejects move when stamina is below cost`() {
        val expensive = balanceLookup(cost = 99)
        val result = reduceMove(world, WorldCommand.MoveAgent(agent, b), expensive, NoBuildings, tick = 1)

        assertEquals(
            WorldRejection.NotEnoughStamina(agent, required = 99, available = 10),
            result.leftOrNull(),
        )
    }

    @Test
    fun `rejects move into an unpainted region`() {
        val unpainted = world.copy(
            regions = world.regions.mapValues { (_, r) -> r.copy(biome = null) },
        )
        val result = reduceMove(unpainted, WorldCommand.MoveAgent(agent, b), flatCost, NoBuildings, tick = 1)

        assertEquals(WorldRejection.UnpaintedRegion(region), result.leftOrNull())
    }

    @Test
    fun `rejects move onto a non-traversable terrain (ocean before boats unlock)`() {
        val sea = world.copy(
            nodes = world.nodes.mapValues { (id, n) ->
                if (id == b) n.copy(terrain = Terrain.OCEAN) else n
            },
        )
        val balance = object : BalanceLookup by flatCost {
            override fun isTraversable(terrain: Terrain): Boolean = terrain != Terrain.OCEAN
        }
        val result = reduceMove(sea, WorldCommand.MoveAgent(agent, b), balance, NoBuildings, tick = 1)

        assertEquals(
            WorldRejection.TerrainNotTraversable(agent, b, Terrain.OCEAN),
            result.leftOrNull(),
        )
    }

    private fun balanceLookup(cost: Int) = object : BalanceLookup {
        override fun moveStaminaCost(biome: Biome, climate: Climate, terrain: Terrain) = cost
        override fun staminaRegenPerTick(climate: Climate) = 0
        override fun resourceSpawnsFor(terrain: Terrain): List<dev.gvart.genesara.world.ResourceSpawnRule> = emptyList()
        override fun harvestStaminaCost(item: dev.gvart.genesara.world.ItemId): Int = 5
        override fun harvestYield(item: dev.gvart.genesara.world.ItemId): Int = 1
        override fun gaugeDrainPerTick(gauge: dev.gvart.genesara.world.Gauge): Int = 0
        override fun gaugeLowThreshold(gauge: dev.gvart.genesara.world.Gauge): Int = 25
        override fun starvationDamagePerTick(): Int = 0
        override fun isWaterSource(terrain: Terrain): Boolean = false
        override fun drinkStaminaCost(): Int = 1
        override fun drinkThirstRefill(): Int = 25
        override fun sleepRegenPerOfflineTick(): Int = 0
        override fun isTraversable(terrain: Terrain): Boolean = true
        override fun roadStaminaMultiplier(): Double = 0.5
    }

    private fun body(stamina: Int, maxStamina: Int) = AgentBody(
        hp = 10, maxHp = 10,
        stamina = stamina, maxStamina = maxStamina,
        mana = 0, maxMana = 0,
    )

    @Test
    fun `move from a node with an active road halves the stamina cost`() {
        val cost10 = balanceLookup(cost = 10)
        val road = activeBuilding(node = a, hint = BuildingCategoryHint.INFRASTRUCTURE_ROAD)
        val buildings = StubBuildingsLookup(byNode = mapOf(a to listOf(road)))

        val result = reduceMove(world, WorldCommand.MoveAgent(agent, b), cost10, buildings, tick = 1)

        result.fold(
            ifLeft = { error("expected Right but got $it") },
            ifRight = { (next, _) -> assertEquals(5, 10 - next.bodyOf(agent)!!.stamina) },
        )
    }

    @Test
    fun `road discount floors stamina cost at 1 — no zero-cost movement`() {
        val cost1 = balanceLookup(cost = 1)
        val road = activeBuilding(node = a, hint = BuildingCategoryHint.INFRASTRUCTURE_ROAD)
        val buildings = StubBuildingsLookup(byNode = mapOf(a to listOf(road)))

        val result = reduceMove(world, WorldCommand.MoveAgent(agent, b), cost1, buildings, tick = 1)

        result.fold(
            ifLeft = { error("expected Right but got $it") },
            ifRight = { (next, _) -> assertEquals(1, 10 - next.bodyOf(agent)!!.stamina) },
        )
    }

    @Test
    fun `move into a non-traversable tile succeeds when an active bridge sits on the destination`() {
        val sea = world.copy(
            nodes = world.nodes.mapValues { (id, n) ->
                if (id == b) n.copy(terrain = Terrain.OCEAN) else n
            },
        )
        val balance = object : BalanceLookup by flatCost {
            override fun isTraversable(terrain: Terrain): Boolean = terrain != Terrain.OCEAN
        }
        val bridge = activeBuilding(node = b, hint = BuildingCategoryHint.INFRASTRUCTURE_BRIDGE)
        val buildings = StubBuildingsLookup(byNode = mapOf(b to listOf(bridge)))

        val result = reduceMove(sea, WorldCommand.MoveAgent(agent, b), balance, buildings, tick = 1)

        result.fold(
            ifLeft = { error("expected Right but got $it") },
            ifRight = { (next, _) -> assertEquals(b, next.positions[agent]) },
        )
    }

    private fun activeBuilding(node: NodeId, hint: BuildingCategoryHint): Building = Building(
        instanceId = UUID.randomUUID(),
        nodeId = node,
        type = when (hint) {
            BuildingCategoryHint.INFRASTRUCTURE_ROAD -> dev.gvart.genesara.world.BuildingType.ROAD
            BuildingCategoryHint.INFRASTRUCTURE_BRIDGE -> dev.gvart.genesara.world.BuildingType.BRIDGE
            else -> error("unsupported hint for this stub")
        },
        status = dev.gvart.genesara.world.BuildingStatus.ACTIVE,
        builtByAgentId = agent,
        builtAtTick = 1L,
        lastProgressTick = 1L,
        progressSteps = 5,
        totalSteps = 5,
        hpCurrent = 50,
        hpMax = 50,
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
            byNode[node].orEmpty().filter { it.status == dev.gvart.genesara.world.BuildingStatus.ACTIVE }
    }
}
