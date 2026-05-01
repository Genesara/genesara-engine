package dev.gvart.genesara.api.internal.rest.worlds

import dev.gvart.genesara.player.RaceId
import dev.gvart.genesara.world.Biome
import dev.gvart.genesara.world.Climate
import dev.gvart.genesara.world.HexUpsert
import dev.gvart.genesara.world.MaybeSet
import dev.gvart.genesara.world.Node
import dev.gvart.genesara.world.Region
import dev.gvart.genesara.world.RegionGeometry
import dev.gvart.genesara.world.RegionId
import dev.gvart.genesara.world.StarterNodeAssignment
import dev.gvart.genesara.world.Vec3
import dev.gvart.genesara.world.World
import dev.gvart.genesara.world.WorldEditingError
import dev.gvart.genesara.world.WorldEditingGateway
import dev.gvart.genesara.world.WorldId
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import kotlin.test.assertEquals

/**
 * Locks in the tri-state PATCH semantics of [WorldEditorController.patchGlobeNode]: a
 * key absent leaves the field untouched, an explicit `null` clears it, a value sets
 * it. The deserializer, the Kotlin-default constructor wiring, and Jackson 3
 * contextualisation all have to align — this is the only end-to-end check.
 */
class WorldEditorPatchControllerTest {

    private val worldId = WorldId(1L)
    private val sphereIndex = 7
    private val region = Region(
        id = RegionId(99L),
        worldId = worldId,
        sphereIndex = sphereIndex,
        biome = Biome.PLAINS,
        climate = Climate.CONTINENTAL,
        centroid = Vec3(0.0, 0.0, 0.0),
        faceVertices = emptyList(),
        neighbors = emptySet(),
    )

    private val gateway = RecordingGateway(region)
    private val mvc = MockMvcBuilders.standaloneSetup(WorldEditorController(gateway)).build()

    @Test
    fun `empty body keeps both fields as Skip`() {
        mvc.patch("/api/worlds/${worldId.value}/nodes/$sphereIndex") {
            contentType = MediaType.APPLICATION_JSON
            content = "{}"
        }.andExpect { status { isOk() } }

        val (b, c) = gateway.lastPatchArgs()
        assertEquals(MaybeSet.Skip, b)
        assertEquals(MaybeSet.Skip, c)
    }

    @Test
    fun `explicit null clears the targeted field but skips the other`() {
        mvc.patch("/api/worlds/${worldId.value}/nodes/$sphereIndex") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"biome": null}"""
        }.andExpect { status { isOk() } }

        val (b, c) = gateway.lastPatchArgs()
        assertEquals(MaybeSet.Set(null), b)
        assertEquals(MaybeSet.Skip, c)
    }

    @Test
    fun `present enum value sets the field`() {
        mvc.patch("/api/worlds/${worldId.value}/nodes/$sphereIndex") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"biome": "FOREST", "climate": "TROPICAL"}"""
        }.andExpect { status { isOk() } }

        val (b, c) = gateway.lastPatchArgs()
        assertEquals(MaybeSet.Set(Biome.FOREST), b)
        assertEquals(MaybeSet.Set(Climate.TROPICAL), c)
    }

    @Test
    fun `unknown enum value returns 400`() {
        mvc.patch("/api/worlds/${worldId.value}/nodes/$sphereIndex") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"biome": "GIBBERISH"}"""
        }.andExpect { status { isBadRequest() } }
    }

    private class RecordingGateway(private val region: Region) : WorldEditingGateway {
        var lastBiome: MaybeSet<Biome?> = MaybeSet.Skip
        var lastClimate: MaybeSet<Climate?> = MaybeSet.Skip

        fun lastPatchArgs(): Pair<MaybeSet<Biome?>, MaybeSet<Climate?>> = lastBiome to lastClimate

        override fun patchRegion(
            worldId: WorldId,
            sphereIndex: Int,
            biome: MaybeSet<Biome?>,
            climate: MaybeSet<Climate?>,
        ): Region {
            lastBiome = biome
            lastClimate = climate
            return region
        }

        override fun listRegions(worldId: WorldId): List<Region> = listOf(region)

        // Unused — fail loudly if any other path reaches them.
        override fun listWorlds(): List<World> = error("not used")
        override fun getWorld(id: WorldId): World? = error("not used")
        override fun createWorld(name: String, requestedNodeCount: Int, nodeSize: Int): World = error("not used")
        override fun getRegion(worldId: WorldId, sphereIndex: Int): Region? = error("not used")
        override fun upsertRegionBiome(
            worldId: WorldId,
            sphereIndex: Int,
            biome: Biome,
            climate: Climate,
            geometry: RegionGeometry?,
        ): Region = error("not used")
        override fun getOrSeedHexes(worldId: WorldId, sphereIndex: Int, radius: Int): List<Node> = error("not used")
        override fun mergeHexes(worldId: WorldId, sphereIndex: Int, tiles: List<HexUpsert>): Int = error("not used")
        override fun listStarterNodes(worldId: WorldId): List<StarterNodeAssignment> = error("not used")
        override fun upsertStarterNode(worldId: WorldId, race: RaceId, nodeId: dev.gvart.genesara.world.NodeId): StarterNodeAssignment = error("not used")
        override fun removeStarterNode(worldId: WorldId, race: RaceId): Boolean = error("not used")
    }

    @Suppress("unused")
    private fun WorldEditingError.unused(): Nothing = error("not thrown by this test")
}
