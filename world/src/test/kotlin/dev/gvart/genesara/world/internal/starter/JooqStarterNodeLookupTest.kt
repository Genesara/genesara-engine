package dev.gvart.genesara.world.internal.starter

import com.zaxxer.hikari.HikariDataSource
import dev.gvart.genesara.player.RaceId
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.internal.jooq.tables.references.NODES
import dev.gvart.genesara.world.internal.jooq.tables.references.NODE_ADJACENCY
import dev.gvart.genesara.world.internal.jooq.tables.references.REGIONS
import dev.gvart.genesara.world.internal.jooq.tables.references.REGION_NEIGHBORS
import dev.gvart.genesara.world.internal.jooq.tables.references.STARTER_NODES
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
import kotlin.test.assertEquals
import kotlin.test.assertNull

@Testcontainers
class JooqStarterNodeLookupTest {

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

    private lateinit var lookup: JooqStarterNodeLookup

    @BeforeEach
    fun resetSchema() {
        // Order matters: starter_nodes → node_adjacency → nodes → region_neighbors → regions → worlds.
        // restartIdentity() resets BIGSERIAL sequences so test rows always start from id 1 — keeps
        // copy-paste templates safe even if downstream tests assert on literal ids.
        dsl.truncate(STARTER_NODES).restartIdentity().cascade().execute()
        dsl.truncate(NODE_ADJACENCY).restartIdentity().cascade().execute()
        dsl.truncate(NODES).restartIdentity().cascade().execute()
        dsl.truncate(REGION_NEIGHBORS).restartIdentity().cascade().execute()
        dsl.truncate(REGIONS).restartIdentity().cascade().execute()
        dsl.truncate(WORLDS).restartIdentity().cascade().execute()
        lookup = JooqStarterNodeLookup(dsl)
    }

    @Test
    fun `returns the node id for a configured race`() {
        val nodeId = seedSingleNode()
        dsl.insertInto(STARTER_NODES)
            .set(STARTER_NODES.RACE_ID, "human_steppe")
            .set(STARTER_NODES.NODE_ID, nodeId.value)
            .execute()

        val resolved = lookup.byRace(RaceId("human_steppe"))

        assertEquals(nodeId, resolved)
    }

    @Test
    fun `returns null when no starter node is configured for the race`() {
        seedSingleNode() // ensure a node exists; just no starter mapping

        val resolved = lookup.byRace(RaceId("human_highland"))

        assertNull(resolved)
    }

    @Test
    fun `returns null when starter_nodes table is entirely empty`() {
        // Exercises the fallback path the spawn flow relies on before world seeding has happened.
        val resolved = lookup.byRace(RaceId("human_commoner"))

        assertNull(resolved)
    }

    /**
     * Seeds the smallest valid graph: one world → one region → one node, returning the node id.
     * `face_vertices` JSONB is set to an empty array since no test inspects it.
     */
    private fun seedSingleNode(): NodeId {
        val worldId = dsl.insertInto(WORLDS)
            .set(WORLDS.NAME, "test-world")
            .set(WORLDS.NODE_COUNT, 1)
            .set(WORLDS.NODE_SIZE, 1)
            .set(WORLDS.FREQUENCY, 1)
            .returning(WORLDS.ID)
            .fetchOne()!!
            .get(WORLDS.ID)!!

        val regionId = dsl.insertInto(REGIONS)
            .set(REGIONS.WORLD_ID, worldId)
            .set(REGIONS.SPHERE_INDEX, 0)
            .set(REGIONS.CENTROID_X, 0.0)
            .set(REGIONS.CENTROID_Y, 0.0)
            .set(REGIONS.CENTROID_Z, 0.0)
            .set(REGIONS.FACE_VERTICES, JSON.json("[]"))
            .returning(REGIONS.ID)
            .fetchOne()!!
            .get(REGIONS.ID)!!

        val nodeId = dsl.insertInto(NODES)
            .set(NODES.REGION_ID, regionId)
            .set(NODES.Q, 0)
            .set(NODES.R, 0)
            .set(NODES.TERRAIN, "PLAINS")
            .returning(NODES.ID)
            .fetchOne()!!
            .get(NODES.ID)!!

        return NodeId(nodeId)
    }
}
