package dev.gvart.genesara.world.internal.worldstate

import com.zaxxer.hikari.HikariDataSource
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.RaceId
import dev.gvart.genesara.world.BodyView
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.StarterNodeLookup
import dev.gvart.genesara.world.internal.jooq.tables.references.AGENT_BODIES
import dev.gvart.genesara.world.internal.testsupport.WorldFlyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Integration test for the new `bodyOf` query on [WorldStateQueryGateway].
 * Other gateway methods are covered by existing reducer / handler tests; this fills the
 * gap created by adding a public [BodyView] projection on top of the internal `AgentBody`.
 */
@Testcontainers
class WorldStateQueryGatewayBodyViewIntegrationTest {

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

    private lateinit var gateway: WorldStateQueryGateway

    @BeforeEach
    fun resetBodies() {
        dsl.truncate(AGENT_BODIES).cascade().execute()
        // bodyOf does not touch static config or starter nodes; construct an empty static
        // config and a no-op starter lookup just to satisfy the constructor.
        val staticConfig = WorldStaticConfig(dsl, JsonMapper.builder().addModule(kotlinModule()).build())
        staticConfig.reload()
        gateway = WorldStateQueryGateway(
            dsl = dsl,
            staticConfig = staticConfig,
            starterNodes = NoOpStarterNodes(),
        )
    }

    @Test
    fun `bodyOf projects every column from agent_bodies into a BodyView`() {
        // Reminder: when adding a column to `agent_bodies` (e.g. armor_class for a future combat
        // slice), update both the seed below AND `BodyView` + `WorldStateQueryGateway.bodyOf`,
        // otherwise `BodyView` will silently drop the new column and this test will keep passing.
        val agent = AgentId(UUID.randomUUID())
        dsl.insertInto(AGENT_BODIES)
            .set(AGENT_BODIES.AGENT_ID, agent.id)
            .set(AGENT_BODIES.HP, 80).set(AGENT_BODIES.MAX_HP, 100)
            .set(AGENT_BODIES.STAMINA, 25).set(AGENT_BODIES.MAX_STAMINA, 50)
            .set(AGENT_BODIES.MANA, 5).set(AGENT_BODIES.MAX_MANA, 15)
            .execute()

        val body = gateway.bodyOf(agent)

        assertEquals(BodyView(hp = 80, maxHp = 100, stamina = 25, maxStamina = 50, mana = 5, maxMana = 15), body)
    }

    @Test
    fun `bodyOf returns null when no row exists for the agent`() {
        val ghost = AgentId(UUID.randomUUID())
        assertNull(gateway.bodyOf(ghost))
    }

    private class NoOpStarterNodes : StarterNodeLookup {
        override fun byRace(race: RaceId): NodeId? = null
    }
}
