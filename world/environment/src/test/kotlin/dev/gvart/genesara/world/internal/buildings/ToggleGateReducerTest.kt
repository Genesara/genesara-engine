package dev.gvart.genesara.world.internal.buildings

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.AgentItemInstancesStore
import dev.gvart.genesara.world.Biome
import dev.gvart.genesara.world.Building
import dev.gvart.genesara.world.BuildingGateStateStore
import dev.gvart.genesara.world.BuildingStatus
import dev.gvart.genesara.world.BuildingType
import dev.gvart.genesara.world.BuildingsStore
import dev.gvart.genesara.world.Climate
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.ItemInstance
import dev.gvart.genesara.world.Node
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.Region
import dev.gvart.genesara.world.RegionId
import dev.gvart.genesara.world.Terrain
import dev.gvart.genesara.world.Vec3
import dev.gvart.genesara.world.WorldId
import dev.gvart.genesara.world.WorldRejection
import dev.gvart.genesara.world.commands.EnvironmentCommand
import dev.gvart.genesara.world.events.EnvironmentEvent
import dev.gvart.genesara.world.internal.body.AgentBody
import dev.gvart.genesara.world.internal.worldstate.WorldState
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ToggleGateReducerTest {

    private val agent = AgentId(UUID.randomUUID())
    private val regionId = RegionId(1L)
    private val node = NodeId(1L)
    private val gateId = UUID.randomUUID()

    private val world = WorldState(
        regions = mapOf(
            regionId to Region(
                id = regionId, worldId = WorldId(1L), sphereIndex = 0,
                biome = Biome.PLAINS, climate = Climate.OCEANIC,
                centroid = Vec3(0.0, 0.0, 1.0), faceVertices = emptyList(), neighbors = emptySet(),
            ),
        ),
        nodes = mapOf(node to Node(node, regionId, q = 0, r = 0, terrain = Terrain.PLAINS, adjacency = emptySet())),
        positions = mapOf(agent to node),
        bodies = mapOf(agent to AgentBody(hp = 50, maxHp = 50, stamina = 50, maxStamina = 50, mana = 0, maxMana = 0)),
        inventories = emptyMap(),
    )

    private val gate = Building(
        instanceId = gateId, nodeId = node, type = BuildingType.GATE, status = BuildingStatus.ACTIVE,
        builtByAgentId = agent, builtAtTick = 1L, lastProgressTick = 5L,
        progressSteps = 10, totalSteps = 10, hpCurrent = 120, hpMax = 120,
    )

    @Test
    fun `happy path — key-holder on gate node toggles closed gate to open`() {
        val keys = StubAgentKeys(holdings = mapOf(agent to setOf(gateId)))
        val states = StubGateStates(initiallyOpen = false)

        val result = reduceToggleGate(world.environment, world.core, EnvironmentCommand.ToggleGate(agent, gateId), StubBuildingsStore(gate), states, keys, dev.gvart.genesara.world.internal.testsupport.InMemoryVisionBlockerCache(), tick = 7L)

        val events = assertNotNull(result.getOrNull()).events
        val event = assertIs<EnvironmentEvent.GateToggled>(events.single())
        assertEquals(gateId, event.gateId)
        assertTrue(event.isOpen)
        assertEquals(true, states.lastFlip)
    }

    @Test
    fun `rejects when agent does not hold a matching key`() {
        val keys = StubAgentKeys(holdings = emptyMap())
        val states = StubGateStates(initiallyOpen = true)

        val result = reduceToggleGate(world.environment, world.core, EnvironmentCommand.ToggleGate(agent, gateId), StubBuildingsStore(gate), states, keys, dev.gvart.genesara.world.internal.testsupport.InMemoryVisionBlockerCache(), tick = 7L)

        assertEquals(WorldRejection.MissingGateKey(agent, gateId), result.leftOrNull())
        assertEquals(null, states.lastFlip)
    }

    @Test
    fun `rejects when agent stands on a different node than the gate`() {
        val keys = StubAgentKeys(holdings = mapOf(agent to setOf(gateId)))
        val states = StubGateStates(initiallyOpen = false)
        val otherNode = NodeId(99L)
        val displaced = world.copy(
            core = world.core.copy(
                positions = mapOf(agent to otherNode),
                nodes = world.nodes + (otherNode to Node(otherNode, regionId, q = 1, r = 0, terrain = Terrain.PLAINS, adjacency = emptySet())),
            ),
        )

        val result = reduceToggleGate(displaced.environment, displaced.core, EnvironmentCommand.ToggleGate(agent, gateId), StubBuildingsStore(gate), states, keys, dev.gvart.genesara.world.internal.testsupport.InMemoryVisionBlockerCache(), tick = 7L)

        assertEquals(WorldRejection.NotOnBuildingNode(agent, gateId), result.leftOrNull())
    }

    @Test
    fun `rejects when the building id does not resolve to an ACTIVE GATE`() {
        val keys = StubAgentKeys(holdings = mapOf(agent to setOf(gateId)))
        val states = StubGateStates(initiallyOpen = false)
        val chestBuilding = gate.copy(type = BuildingType.STORAGE_CHEST)

        val result = reduceToggleGate(world.environment, world.core, EnvironmentCommand.ToggleGate(agent, gateId), StubBuildingsStore(chestBuilding), states, keys, dev.gvart.genesara.world.internal.testsupport.InMemoryVisionBlockerCache(), tick = 7L)

        assertEquals(WorldRejection.GateNotFound(agent, gateId), result.leftOrNull())
    }

    private class StubBuildingsStore(private val one: Building?) : BuildingsStore {
        override fun insert(building: Building) = error("not used")
        override fun findById(id: UUID): Building? = one?.takeIf { it.instanceId == id }
        override fun findInProgress(node: NodeId, agent: AgentId, type: BuildingType): Building? = null
        override fun findAnyAtNodeOfType(node: NodeId, type: BuildingType): Building? = null
        override fun listAtNode(node: NodeId): List<Building> = listOfNotNull(one)
        override fun listByNodes(nodes: Set<NodeId>): Map<NodeId, List<Building>> = emptyMap()
        override fun advanceProgress(id: UUID, newProgress: Int, asOfTick: Long): Building? = null
        override fun complete(id: UUID, asOfTick: Long): Building? = null
    }

    private class StubGateStates(initiallyOpen: Boolean) : BuildingGateStateStore {
        private var open: Boolean = initiallyOpen
        var lastFlip: Boolean? = null
            private set

        override fun insertClosed(gateInstanceId: UUID) = Unit
        override fun isOpen(gateInstanceId: UUID): Boolean? = open
        override fun toggle(gateInstanceId: UUID): Boolean? {
            open = !open
            lastFlip = open
            return open
        }
    }

    private class StubAgentKeys(private val holdings: Map<AgentId, Set<UUID>>) : dev.gvart.genesara.world.internal.testsupport.InMemoryAgentItemInstancesStore() {
        override fun agentHoldsKeyFor(agent: AgentId, gateInstanceId: UUID): Boolean =
            holdings[agent].orEmpty().contains(gateInstanceId)
    }

    @Test
    fun `toggle triggers vision-blocker cache recompute for the gate's tile`() {
        val keys = StubAgentKeys(holdings = mapOf(agent to setOf(gateId)))
        val states = StubGateStates(initiallyOpen = false)
        val recomputed = mutableListOf<NodeId>()
        val cache = object : dev.gvart.genesara.world.internal.vision.VisionBlockerCache {
            override fun blockerHeights(nodes: Set<NodeId>): Map<NodeId, Int> = emptyMap()
            override fun recomputeForNode(nodeId: NodeId) { recomputed += nodeId }
            override fun seedAll() = Unit
            override fun flush() = Unit
        }

        reduceToggleGate(world.environment, world.core, EnvironmentCommand.ToggleGate(agent, gateId), StubBuildingsStore(gate), states, keys, cache, tick = 7L)

        assertEquals(listOf(node), recomputed)
    }
}

private val UNUSED_ITEM_ID = ItemId("UNUSED")
