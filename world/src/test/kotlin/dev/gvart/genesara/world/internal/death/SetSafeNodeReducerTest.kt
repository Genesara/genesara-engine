package dev.gvart.genesara.world.internal.death

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.AgentSafeNodeGateway
import dev.gvart.genesara.world.Biome
import dev.gvart.genesara.world.Climate
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
import dev.gvart.genesara.world.internal.worldstate.WorldState
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SetSafeNodeReducerTest {

    private val agent = AgentId(UUID.randomUUID())
    private val regionId = RegionId(1L)
    private val nodeId = NodeId(42L)

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
    fun `set_safe_node binds the agent's current node and emits SafeNodeSet`() {
        val state = stateWith(positioned = true)
        val gateway = RecordingGateway()

        val result = reduceSetSafeNode(state, WorldCommand.SetSafeNode(agent), gateway, tick = 7)

        val (next, event) = assertIs<arrow.core.Either.Right<Pair<WorldState, WorldEvent>>>(result).value
        assertEquals(state, next)  // state unchanged — gateway carries the side-effect
        val set = assertIs<WorldEvent.SafeNodeSet>(event)
        assertEquals(agent, set.agent)
        assertEquals(nodeId, set.at)
        assertEquals(7L, set.tick)
        assertEquals(listOf(Triple(agent, nodeId, 7L)), gateway.calls)
    }

    @Test
    fun `set_safe_node rejects when the agent is not in the world`() {
        val state = stateWith(positioned = false)
        val gateway = RecordingGateway()

        val result = reduceSetSafeNode(state, WorldCommand.SetSafeNode(agent), gateway, tick = 1)

        assertEquals(WorldRejection.NotInWorld(agent), result.leftOrNull())
        assertEquals(emptyList(), gateway.calls, "gateway must not be touched on rejection")
    }

    private fun stateWith(positioned: Boolean): WorldState = WorldState(
        regions = mapOf(regionId to region),
        nodes = mapOf(nodeId to node),
        positions = if (positioned) mapOf(agent to nodeId) else emptyMap(),
        bodies = mapOf(agent to AgentBody(hp = 50, maxHp = 50, stamina = 0, maxStamina = 0, mana = 0, maxMana = 0)),
        inventories = emptyMap(),
    )

    private class RecordingGateway : AgentSafeNodeGateway {
        val calls = mutableListOf<Triple<AgentId, NodeId, Long>>()
        override fun set(agentId: AgentId, nodeId: NodeId, tick: Long) {
            calls += Triple(agentId, nodeId, tick)
        }
        override fun find(agentId: AgentId): NodeId? = null
        override fun clear(agentId: AgentId) {}
    }
}
