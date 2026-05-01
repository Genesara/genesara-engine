package dev.gvart.genesara.api.internal.rest.worlds

import dev.gvart.genesara.player.RaceId
import dev.gvart.genesara.world.Biome
import dev.gvart.genesara.world.Climate
import dev.gvart.genesara.world.HexUpsert
import dev.gvart.genesara.world.MaybeSet
import dev.gvart.genesara.world.Node
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.Region
import dev.gvart.genesara.world.RegionGeometry
import dev.gvart.genesara.world.StarterNodeAssignment
import dev.gvart.genesara.world.World
import dev.gvart.genesara.world.WorldEditingGateway
import dev.gvart.genesara.world.WorldId
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import kotlin.test.assertEquals

class StarterNodesControllerTest {

    private val worldId = 1L
    private val race = RaceId("HUMAN_NORTHERN")
    private val node = NodeId(42L)

    @Test
    fun `list maps assignments to DTOs in catalog order`() {
        val recorder = RecordingGateway(
            list = listOf(
                StarterNodeAssignment(race = RaceId("HUMAN_NORTHERN"), nodeId = NodeId(11L)),
                StarterNodeAssignment(race = RaceId("HUMAN_SOUTHERN"), nodeId = NodeId(22L)),
            ),
        )
        val controller = StarterNodesController(recorder)

        val response = controller.list(worldId)

        assertEquals(
            listOf(
                StarterNodeDto(raceId = "HUMAN_NORTHERN", nodeId = 11L),
                StarterNodeDto(raceId = "HUMAN_SOUTHERN", nodeId = 22L),
            ),
            response,
        )
    }

    @Test
    fun `upsert delegates to gateway with parsed ids and returns the persisted assignment`() {
        val recorder = RecordingGateway()
        val controller = StarterNodesController(recorder)

        val response = controller.upsert(worldId, race.value, UpsertStarterNodeRequest(nodeId = node.value))

        assertEquals(StarterNodeDto("HUMAN_NORTHERN", 42L), response)
        assertEquals(listOf(Triple(WorldId(worldId), race, node)), recorder.upserts)
    }

    @Test
    fun `delete returns 204 when a row was removed`() {
        val recorder = RecordingGateway(removeReturns = true)
        val controller = StarterNodesController(recorder)

        val response = controller.remove(worldId, race.value)

        assertEquals(HttpStatus.NO_CONTENT, HttpStatus.valueOf(response.statusCode.value()))
        assertEquals(listOf(WorldId(worldId) to race), recorder.removes)
    }

    @Test
    fun `delete returns 404 when no mapping existed`() {
        val controller = StarterNodesController(RecordingGateway(removeReturns = false))

        val response = controller.remove(worldId, race.value)

        assertEquals(HttpStatus.NOT_FOUND, HttpStatus.valueOf(response.statusCode.value()))
    }

    /**
     * Spying gateway that records calls and lets each test pre-canned return values.
     * Other methods on the interface throw — the controller doesn't use them, so any
     * accidental call (caught by the contract widening over time) is loud, not silent.
     */
    private class RecordingGateway(
        private val list: List<StarterNodeAssignment> = emptyList(),
        private val removeReturns: Boolean = true,
    ) : WorldEditingGateway {
        val upserts = mutableListOf<Triple<WorldId, RaceId, NodeId>>()
        val removes = mutableListOf<Pair<WorldId, RaceId>>()

        override fun listStarterNodes(worldId: WorldId): List<StarterNodeAssignment> = list

        override fun upsertStarterNode(worldId: WorldId, race: RaceId, nodeId: NodeId): StarterNodeAssignment {
            upserts += Triple(worldId, race, nodeId)
            return StarterNodeAssignment(race, nodeId)
        }

        override fun removeStarterNode(worldId: WorldId, race: RaceId): Boolean {
            removes += worldId to race
            return removeReturns
        }

        // Unused on this surface; throw loudly if anything reaches them.
        override fun listWorlds(): List<World> = error("not used")
        override fun getWorld(id: WorldId): World? = error("not used")
        override fun createWorld(name: String, requestedNodeCount: Int, nodeSize: Int): World = error("not used")
        override fun listRegions(worldId: WorldId): List<Region> = error("not used")
        override fun getRegion(worldId: WorldId, sphereIndex: Int): Region? = error("not used")
        override fun upsertRegionBiome(
            worldId: WorldId,
            sphereIndex: Int,
            biome: Biome,
            climate: Climate,
            geometry: RegionGeometry?,
        ): Region = error("not used")
        override fun patchRegion(
            worldId: WorldId,
            sphereIndex: Int,
            biome: MaybeSet<Biome?>,
            climate: MaybeSet<Climate?>,
        ): Region = error("not used")
        override fun getOrSeedHexes(worldId: WorldId, sphereIndex: Int, radius: Int): List<Node> = error("not used")
        override fun mergeHexes(worldId: WorldId, sphereIndex: Int, tiles: List<HexUpsert>): Int = error("not used")
    }
}
