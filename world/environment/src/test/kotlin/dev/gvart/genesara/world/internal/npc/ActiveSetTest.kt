package dev.gvart.genesara.world.internal.npc

import dev.gvart.genesara.world.Biome
import dev.gvart.genesara.world.Climate
import dev.gvart.genesara.world.Node
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.Region
import dev.gvart.genesara.world.RegionId
import dev.gvart.genesara.world.Terrain
import dev.gvart.genesara.world.Vec3
import dev.gvart.genesara.world.WorldId
import dev.gvart.genesara.world.internal.worldstate.WorldState
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ActiveSetTest {

    private val region = Region(
        id = RegionId(1L),
        worldId = WorldId(1L),
        sphereIndex = 0,
        biome = Biome.PLAINS,
        climate = Climate.OCEANIC,
        centroid = Vec3(0.0, 0.0, 1.0),
        faceVertices = emptyList(),
        neighbors = emptySet(),
    )

    /** Build a linear chain `n0 — n1 — n2 — ...` with bidirectional adjacency. */
    private fun chain(length: Int): WorldState {
        val nodes = (0 until length).associate { i ->
            val id = NodeId(i.toLong())
            val adj = buildSet {
                if (i > 0) add(NodeId((i - 1).toLong()))
                if (i < length - 1) add(NodeId((i + 1).toLong()))
            }
            id to Node(id, region.id, q = i, r = 0, terrain = Terrain.PLAINS, adjacency = adj)
        }
        return WorldState(
            regions = mapOf(region.id to region),
            nodes = nodes,
            positions = emptyMap(),
            bodies = emptyMap(),
            inventories = emptyMap(),
        )
    }

    @Test
    fun `active set with radius zero returns only the anchor`() {
        val state = chain(5)
        val active = activeNodeSet(state, listOf(NodeId(2L)), hopRadius = 0)
        assertEquals(setOf(NodeId(2L)), active)
    }

    @Test
    fun `active set expands by radius hops over adjacency`() {
        val state = chain(10)
        val active = activeNodeSet(state, listOf(NodeId(4L)), hopRadius = 2)
        assertEquals(setOf(NodeId(2L), NodeId(3L), NodeId(4L), NodeId(5L), NodeId(6L)), active)
    }

    @Test
    fun `active set unions multiple anchors without double-counting`() {
        val state = chain(10)
        val active = activeNodeSet(state, listOf(NodeId(1L), NodeId(8L)), hopRadius = 1)
        assertEquals(
            setOf(NodeId(0L), NodeId(1L), NodeId(2L), NodeId(7L), NodeId(8L), NodeId(9L)),
            active,
        )
    }

    @Test
    fun `active set tolerates anchors with no adjacency entry`() {
        val state = chain(3)
        val active = activeNodeSet(state, listOf(NodeId(99L)), hopRadius = 4)
        assertEquals(setOf(NodeId(99L)), active)
    }

    @Test
    fun `hop distance returns minimum BFS depth`() {
        val state = chain(10)
        assertEquals(0, hopDistance(state, NodeId(3L), NodeId(3L), maxHops = 5))
        assertEquals(1, hopDistance(state, NodeId(3L), NodeId(4L), maxHops = 5))
        assertEquals(4, hopDistance(state, NodeId(3L), NodeId(7L), maxHops = 5))
    }

    @Test
    fun `hop distance returns negative when target sits past max hops`() {
        val state = chain(10)
        assertTrue(hopDistance(state, NodeId(0L), NodeId(9L), maxHops = 4) < 0)
    }
}
