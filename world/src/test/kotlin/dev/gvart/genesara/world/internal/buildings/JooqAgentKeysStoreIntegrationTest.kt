package dev.gvart.genesara.world.internal.buildings

import com.zaxxer.hikari.HikariDataSource
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.AgentKeyInstance
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.internal.jooq.tables.references.AGENT_KEYS
import dev.gvart.genesara.world.internal.testsupport.WorldFlyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.exception.DataAccessException
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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Testcontainers
class JooqAgentKeysStoreIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("agent_keys_it")
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

    private lateinit var store: JooqAgentKeysStore
    private val agent = AgentId(UUID.randomUUID())
    private val otherAgent = AgentId(UUID.randomUUID())
    private val gateId = UUID.randomUUID()

    @BeforeEach
    fun reset() {
        dsl.truncate(AGENT_KEYS).cascade().execute()
        store = JooqAgentKeysStore(dsl)
    }

    @Test
    fun `insert and findById round-trip all fields`() {
        val key = sampleKey()
        store.insert(key)

        assertEquals(key, store.findById(key.instanceId))
    }

    @Test
    fun `findById returns null for a missing instance`() {
        assertNull(store.findById(UUID.randomUUID()))
    }

    @Test
    fun `listByAgent returns only the calling agent's keys`() {
        val mine1 = sampleKey()
        val mine2 = sampleKey(gateInstanceId = UUID.randomUUID())
        val theirs = sampleKey(agentId = otherAgent)
        listOf(mine1, mine2, theirs).forEach { store.insert(it) }

        val result = store.listByAgent(agent)

        assertEquals(setOf(mine1.instanceId, mine2.instanceId), result.map { it.instanceId }.toSet())
    }

    @Test
    fun `agentHoldsKeyFor returns true when the agent holds a key for that gate`() {
        store.insert(sampleKey())

        assertTrue(store.agentHoldsKeyFor(agent, gateId))
    }

    @Test
    fun `agentHoldsKeyFor returns false for the wrong agent`() {
        store.insert(sampleKey())

        assertFalse(store.agentHoldsKeyFor(otherAgent, gateId))
    }

    @Test
    fun `agentHoldsKeyFor returns false for the wrong gate`() {
        store.insert(sampleKey())

        assertFalse(store.agentHoldsKeyFor(agent, UUID.randomUUID()))
    }

    @Test
    fun `duplicate instance_id raises DataAccessException on PK conflict`() {
        val key = sampleKey()
        store.insert(key)

        assertFailsWith<DataAccessException> { store.insert(key) }
    }

    private fun sampleKey(
        instanceId: UUID = UUID.randomUUID(),
        agentId: AgentId = agent,
        gateInstanceId: UUID = gateId,
    ) = AgentKeyInstance(
        instanceId = instanceId,
        agentId = agentId,
        itemId = ItemId("GATE_KEY"),
        gateInstanceId = gateInstanceId,
        createdAtTick = 1L,
    )
}
