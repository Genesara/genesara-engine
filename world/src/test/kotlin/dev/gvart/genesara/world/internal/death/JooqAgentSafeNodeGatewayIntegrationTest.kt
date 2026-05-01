package dev.gvart.genesara.world.internal.death

import com.zaxxer.hikari.HikariDataSource
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.Biome
import dev.gvart.genesara.world.Climate
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.Terrain
import dev.gvart.genesara.world.internal.jooq.tables.references.AGENT_SAFE_NODES
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
import kotlin.test.assertNull

/**
 * End-to-end coverage for [JooqAgentSafeNodeGateway] — verifies the upsert
 * shape (one row per agent, set replaces) and the cascade on node delete that
 * keeps stale checkpoint rows from outliving their target node.
 */
@Testcontainers
class JooqAgentSafeNodeGatewayIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("safenode_it")
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

    private lateinit var gateway: JooqAgentSafeNodeGateway
    private val agent = AgentId(UUID.randomUUID())
    private val otherAgent = AgentId(UUID.randomUUID())
    private var nodeA: Long = 0L
    private var nodeB: Long = 0L

    @BeforeEach
    fun reset() {
        dsl.truncate(AGENT_SAFE_NODES).cascade().execute()
        dsl.truncate(NODES).cascade().execute()
        dsl.truncate(REGIONS).cascade().execute()
        dsl.truncate(WORLDS).cascade().execute()
        val worldId = dsl.insertInto(WORLDS)
            .set(WORLDS.NAME, "safenode-test")
            .set(WORLDS.NODE_COUNT, 1)
            .set(WORLDS.NODE_SIZE, 1)
            .set(WORLDS.FREQUENCY, 1)
            .returningResult(WORLDS.ID)
            .fetchOne()!!.value1()!!
        val regionId = dsl.insertInto(REGIONS)
            .set(REGIONS.WORLD_ID, worldId)
            .set(REGIONS.SPHERE_INDEX, 0)
            .set(REGIONS.BIOME, Biome.PLAINS.name)
            .set(REGIONS.CLIMATE, Climate.CONTINENTAL.name)
            .set(REGIONS.CENTROID_X, 0.0)
            .set(REGIONS.CENTROID_Y, 0.0)
            .set(REGIONS.CENTROID_Z, 1.0)
            .set(REGIONS.FACE_VERTICES, JSON.valueOf("[]"))
            .returningResult(REGIONS.ID)
            .fetchOne()!!.value1()!!
        nodeA = insertNode(regionId, q = 0, r = 0)
        nodeB = insertNode(regionId, q = 1, r = 0)
        gateway = JooqAgentSafeNodeGateway(dsl)
    }

    @Test
    fun `find returns null for an agent who has never set a checkpoint`() {
        assertNull(gateway.find(agent))
    }

    @Test
    fun `set inserts and find returns the node`() {
        gateway.set(agent, NodeId(nodeA), tick = 5L)
        assertEquals(NodeId(nodeA), gateway.find(agent))
    }

    @Test
    fun `set is upsert — second call replaces the first`() {
        // Pin the contract: checkpoints overwrite. An agent who walks into a
        // new safe node and re-marks should not need to clear the old row first.
        gateway.set(agent, NodeId(nodeA), tick = 5L)
        gateway.set(agent, NodeId(nodeB), tick = 9L)

        assertEquals(NodeId(nodeB), gateway.find(agent))
    }

    @Test
    fun `each agent has independent checkpoints`() {
        gateway.set(agent, NodeId(nodeA), tick = 1L)
        gateway.set(otherAgent, NodeId(nodeB), tick = 2L)

        assertEquals(NodeId(nodeA), gateway.find(agent))
        assertEquals(NodeId(nodeB), gateway.find(otherAgent))
    }

    @Test
    fun `node deletion cascades and the safe-node row vanishes`() {
        gateway.set(agent, NodeId(nodeA), tick = 1L)

        // Admin deletes the node. Without the FK + ON DELETE CASCADE we'd be
        // left with a stale checkpoint pointing at a non-existent node;
        // future respawn would resolve a node that's not in the world graph.
        dsl.deleteFrom(NODES).where(NODES.ID.eq(nodeA)).execute()

        assertNull(gateway.find(agent), "cascade should have removed the safe-node row")
    }

    private fun insertNode(regionId: Long, q: Int, r: Int): Long =
        dsl.insertInto(NODES)
            .set(NODES.REGION_ID, regionId)
            .set(NODES.Q, q)
            .set(NODES.R, r)
            .set(NODES.TERRAIN, Terrain.PLAINS.name)
            .returningResult(NODES.ID)
            .fetchOne()!!.value1()!!
}
