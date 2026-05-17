package dev.gvart.genesara.world.internal.memory

import com.zaxxer.hikari.HikariDataSource
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.Biome
import dev.gvart.genesara.world.Climate
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.NodeMemoryUpdate
import dev.gvart.genesara.world.Terrain
import dev.gvart.genesara.world.internal.jooq.tables.references.AGENT_NODE_MEMORY
import dev.gvart.genesara.world.internal.jooq.tables.references.NODES
import dev.gvart.genesara.world.internal.jooq.tables.references.REGIONS
import dev.gvart.genesara.world.internal.jooq.tables.references.WORLDS
import dev.gvart.genesara.world.internal.testsupport.WorldFlyway
import org.jooq.DSLContext
import org.jooq.JSON
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end coverage for [JooqAgentMapMemoryGateway] — the per-agent fog-of-war recall
 * substrate that backs `get_map`. Verifies upsert idempotency, first-seen-tick locking,
 * cascade delete on node removal, and the recall projection through the regions join.
 */
@Testcontainers
class JooqAgentMapMemoryGatewayIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("world_it")
            .withUsername("test")
            .withPassword("test")

        private lateinit var dataSource: HikariDataSource
        private lateinit var dsl: DSLContext

        @BeforeAll
        @JvmStatic
        fun migrateOnce() {
            dataSource = WorldFlyway.pooledDataSource(postgres)
            WorldFlyway.migrate(dataSource)
            dsl = DSL.using(dataSource, SQLDialect.POSTGRES)
        }

        @AfterAll
        @JvmStatic
        fun closePool() {
            dataSource.close()
        }
    }

    private lateinit var gateway: JooqAgentMapMemoryGateway
    private val agent = AgentId(UUID.randomUUID())
    private val otherAgent = AgentId(UUID.randomUUID())
    private var nodeA: Long = 0L
    private var nodeB: Long = 0L
    private var nodeC: Long = 0L

    @BeforeEach
    fun reset() {
        dsl.truncate(AGENT_NODE_MEMORY).cascade().execute()
        dsl.truncate(NODES).cascade().execute()
        dsl.truncate(REGIONS).cascade().execute()
        dsl.truncate(WORLDS).cascade().execute()
        val worldId = dsl.insertInto(WORLDS)
            .set(WORLDS.NAME, "memory-test")
            .set(WORLDS.NODE_COUNT, 1)
            .set(WORLDS.NODE_SIZE, 1)
            .set(WORLDS.FREQUENCY, 1)
            .returningResult(WORLDS.ID)
            .fetchOne()!!.value1()!!
        val regionId = dsl.insertInto(REGIONS)
            .set(REGIONS.WORLD_ID, worldId)
            .set(REGIONS.SPHERE_INDEX, 0)
            .set(REGIONS.BIOME, Biome.FOREST.name)
            .set(REGIONS.CLIMATE, Climate.CONTINENTAL.name)
            .set(REGIONS.CENTROID_X, 0.0)
            .set(REGIONS.CENTROID_Y, 0.0)
            .set(REGIONS.CENTROID_Z, 1.0)
            .set(REGIONS.FACE_VERTICES, JSON.valueOf("[]"))
            .returningResult(REGIONS.ID)
            .fetchOne()!!.value1()!!
        nodeA = insertNode(regionId, q = 0, r = 0, terrain = Terrain.FOREST)
        nodeB = insertNode(regionId, q = 1, r = 0, terrain = Terrain.PLAINS)
        nodeC = insertNode(regionId, q = 2, r = 0, terrain = Terrain.MOUNTAIN)
        gateway = JooqAgentMapMemoryGateway(dsl)
    }

    @Test
    fun `recall is empty for an agent who has never had vision`() {
        assertEquals(emptyList(), gateway.recall(agent))
    }

    @Test
    fun `recordVisible inserts new pairs and recall projects the snapshotted biome`() {
        gateway.recordVisible(
            agent,
            listOf(
                NodeMemoryUpdate(nodeId = NodeId(nodeA), terrain = Terrain.FOREST, biome = Biome.FOREST),
                NodeMemoryUpdate(nodeId = NodeId(nodeB), terrain = Terrain.PLAINS, biome = Biome.PLAINS),
            ),
            tick = 5L,
        )

        val recalled = gateway.recall(agent)

        assertEquals(2, recalled.size)
        val first = recalled.first { it.nodeId.value == nodeA }
        assertEquals(Terrain.FOREST, first.terrain)
        assertEquals(Biome.FOREST, first.biome)
        assertEquals(5L, first.firstSeenTick)
        assertEquals(5L, first.lastSeenTick)
        // Distinct biomes per row — verifying the recall isn't projecting a single
        // joined region biome but the per-row snapshot.
        val second = recalled.first { it.nodeId.value == nodeB }
        assertEquals(Biome.PLAINS, second.biome)
    }

    @Test
    fun `recordVisible advances last_seen_tick on an existing pair without resetting first_seen`() {
        gateway.recordVisible(agent, listOf(NodeMemoryUpdate(NodeId(nodeA), Terrain.FOREST, Biome.FOREST)), tick = 1L)
        gateway.recordVisible(agent, listOf(NodeMemoryUpdate(NodeId(nodeA), Terrain.PLAINS, Biome.PLAINS)), tick = 9L)

        val recalled = gateway.recall(agent).single()
        assertEquals(1L, recalled.firstSeenTick, "first_seen_tick must lock on the initial insert")
        assertEquals(9L, recalled.lastSeenTick, "last_seen_tick must advance to the latest record")
        assertEquals(Terrain.PLAINS, recalled.terrain, "last_terrain reflects the most recent sighting")
        assertEquals(Biome.PLAINS, recalled.biome, "last_biome reflects the most recent sighting")
    }

    @Test
    fun `recordVisible at the same tick is a no-op idempotent rewrite`() {
        // Two look_around calls at the same tick should converge to a single row with
        // first == last == that tick. Pinning the contract because spawn flows can
        // chain look_around calls within a single tick.
        gateway.recordVisible(agent, listOf(NodeMemoryUpdate(NodeId(nodeA), Terrain.FOREST, Biome.FOREST)), tick = 5L)
        gateway.recordVisible(agent, listOf(NodeMemoryUpdate(NodeId(nodeA), Terrain.FOREST, Biome.FOREST)), tick = 5L)

        val recalled = gateway.recall(agent).single()
        assertEquals(5L, recalled.firstSeenTick)
        assertEquals(5L, recalled.lastSeenTick)
    }

    @Test
    fun `recall is per-agent — an agent does not see another agent's memory`() {
        gateway.recordVisible(agent, listOf(NodeMemoryUpdate(NodeId(nodeA), Terrain.FOREST, Biome.FOREST)), tick = 1L)
        gateway.recordVisible(otherAgent, listOf(NodeMemoryUpdate(NodeId(nodeB), Terrain.PLAINS, Biome.PLAINS)), tick = 2L)

        assertEquals(listOf(NodeId(nodeA)), gateway.recall(agent).map { it.nodeId })
        assertEquals(listOf(NodeId(nodeB)), gateway.recall(otherAgent).map { it.nodeId })
    }

    @Test
    fun `empty updates collection is a cheap no-op`() {
        gateway.recordVisible(agent, emptyList(), tick = 5L)
        assertEquals(emptyList(), gateway.recall(agent))
    }

    @Test
    fun `node deletion cascades through agent_node_memory rows`() {
        gateway.recordVisible(
            agent,
            listOf(
                NodeMemoryUpdate(NodeId(nodeA), Terrain.FOREST, Biome.FOREST),
                NodeMemoryUpdate(NodeId(nodeB), Terrain.PLAINS, Biome.PLAINS),
            ),
            tick = 1L,
        )

        // Drop nodeA — admin tooling can delete a node and the agent's memory of it
        // must follow rather than leave dangling rows / 404s on recall.
        dsl.deleteFrom(NODES).where(NODES.ID.eq(nodeA)).execute()

        val recalled = gateway.recall(agent)
        assertEquals(listOf(NodeId(nodeB)), recalled.map { it.nodeId })
    }

    @Test
    fun `null biome on the snapshotted update round-trips as null`() {
        // Unpainted regions can still be observed (they have a terrain). Recall must
        // not blow up on a null biome.
        gateway.recordVisible(
            agent,
            listOf(NodeMemoryUpdate(NodeId(nodeA), Terrain.FOREST, biome = null)),
            tick = 1L,
        )

        val recalled = gateway.recall(agent).single()
        assertEquals(null, recalled.biome)
    }

    @Test
    fun `recall reflects the snapshotted biome even after the live region biome changes`() {
        // Stale-recall semantics: a re-paint of the region's biome (e.g. admin edits)
        // must NOT retroactively change what the agent remembers seeing.
        gateway.recordVisible(
            agent,
            listOf(NodeMemoryUpdate(NodeId(nodeA), Terrain.FOREST, biome = Biome.FOREST)),
            tick = 1L,
        )
        // Admin re-paints the region to TUNDRA.
        dsl.update(dev.gvart.genesara.world.internal.jooq.tables.references.REGIONS)
            .set(dev.gvart.genesara.world.internal.jooq.tables.references.REGIONS.BIOME, Biome.TUNDRA.name)
            .execute()

        val recalled = gateway.recall(agent).single()
        assertEquals(Biome.FOREST, recalled.biome, "recall must reflect what the agent saw, not the live re-painted biome")
    }

    @Test
    fun `recall ordering is stable on node_id`() {
        // Insert in non-monotonic order to exercise the ORDER BY.
        gateway.recordVisible(agent, listOf(NodeMemoryUpdate(NodeId(nodeC), Terrain.MOUNTAIN, Biome.MOUNTAIN)), tick = 1L)
        gateway.recordVisible(agent, listOf(NodeMemoryUpdate(NodeId(nodeA), Terrain.FOREST, Biome.FOREST)), tick = 2L)
        gateway.recordVisible(agent, listOf(NodeMemoryUpdate(NodeId(nodeB), Terrain.PLAINS, Biome.PLAINS)), tick = 3L)

        val ids = gateway.recall(agent).map { it.nodeId.value }
        assertTrue(ids == ids.sorted(), "recall must be sorted by node_id; got $ids")
    }

    private fun insertNode(regionId: Long, q: Int, r: Int, terrain: Terrain): Long =
        dsl.insertInto(NODES)
            .set(NODES.REGION_ID, regionId)
            .set(NODES.Q, q)
            .set(NODES.R, r)
            .set(NODES.TERRAIN, terrain.name)
            .returningResult(NODES.ID)
            .fetchOne()!!.value1()!!
}
