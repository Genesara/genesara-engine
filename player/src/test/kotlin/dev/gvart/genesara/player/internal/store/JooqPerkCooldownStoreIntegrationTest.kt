package dev.gvart.genesara.player.internal.store

import com.zaxxer.hikari.HikariDataSource
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.PerkId
import dev.gvart.genesara.player.internal.jooq.tables.references.AGENTS
import dev.gvart.genesara.player.internal.jooq.tables.references.AGENT_PERK_COOLDOWNS
import dev.gvart.genesara.player.internal.testsupport.PlayerFlyway
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
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Testcontainers
class JooqPerkCooldownStoreIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("player_perk_cd_it")
            .withUsername("test")
            .withPassword("test")

        private lateinit var dataSource: HikariDataSource
        private lateinit var dsl: DSLContext

        @BeforeAll
        @JvmStatic
        fun migrateOnce() {
            dataSource = PlayerFlyway.pooledDataSource(postgres)
            PlayerFlyway.migrate(dataSource)
            dsl = DSL.using(dataSource, SQLDialect.POSTGRES)
        }

        @AfterAll
        @JvmStatic
        fun closePool() {
            dataSource.close()
        }
    }

    private val perk = PerkId("SWORD_BLEEDER")

    private lateinit var store: JooqPerkCooldownStore
    private var agent: AgentId = AgentId(UUID.randomUUID())

    @BeforeEach
    fun resetTables() {
        dsl.truncate(AGENT_PERK_COOLDOWNS).cascade().execute()
        dsl.truncate(AGENTS).cascade().execute()
        agent = createAgent()
        store = JooqPerkCooldownStore(dsl)
    }

    @Test
    fun `unarmed perk is ready by default`() {
        assertTrue(store.isReady(agent, perk, tick = 0L))
    }

    @Test
    fun `arm blocks until the gate tick`() {
        store.arm(agent, perk, untilTick = 10L, currentTick = 0L)

        assertFalse(store.isReady(agent, perk, tick = 9L))
        assertTrue(store.isReady(agent, perk, tick = 10L), "gate tick is inclusive — perk fires at exactly until-tick")
        assertTrue(store.isReady(agent, perk, tick = 11L))
    }

    @Test
    fun `re-arm overwrites the prior gate tick`() {
        store.arm(agent, perk, untilTick = 5L, currentTick = 0L)
        store.arm(agent, perk, untilTick = 20L, currentTick = 4L)

        assertFalse(store.isReady(agent, perk, tick = 10L))
        assertTrue(store.isReady(agent, perk, tick = 20L))
        assertEquals(1, dsl.fetchCount(AGENT_PERK_COOLDOWNS), "single (agent, perk) row is upserted")
    }

    @Test
    fun `cooldowns are scoped per agent and per perk`() {
        val otherAgent = createAgent()
        val otherPerk = PerkId("SWORD_PRECISION")

        store.arm(agent, perk, untilTick = 100L, currentTick = 0L)

        assertFalse(store.isReady(agent, perk, tick = 50L))
        assertTrue(store.isReady(otherAgent, perk, tick = 50L))
        assertTrue(store.isReady(agent, otherPerk, tick = 50L))
    }

    @Test
    fun `byAgents batches every armed perk for the requested agents`() {
        val otherAgent = createAgent()
        val absent = createAgent()
        val perkA = PerkId("SWORD_BLEEDER")
        val perkB = PerkId("SWORD_PRECISION")

        store.arm(agent, perkA, untilTick = 10L, currentTick = 0L)
        store.arm(agent, perkB, untilTick = 25L, currentTick = 0L)
        store.arm(otherAgent, perkA, untilTick = 7L, currentTick = 0L)

        val loaded = store.byAgents(setOf(agent, otherAgent, absent))

        assertEquals(mapOf(perkA to 10L, perkB to 25L), loaded[agent])
        assertEquals(mapOf(perkA to 7L), loaded[otherAgent])
        kotlin.test.assertNull(loaded[absent], "agents with no cooldowns are absent from the result map")
    }

    private fun createAgent(): AgentId {
        val id = AgentId(UUID.randomUUID())
        dsl.insertInto(AGENTS)
            .set(AGENTS.ID, id.id)
            .set(AGENTS.OWNER_ID, UUID.randomUUID())
            .set(AGENTS.NAME, "test-${id.id.toString().take(6)}")
            .set(AGENTS.RACE_ID, "human_commoner")
            .set(AGENTS.LEVEL, 1)
            .set(AGENTS.XP_CURRENT, 0)
            .set(AGENTS.XP_TO_NEXT, 100)
            .set(AGENTS.UNSPENT_ATTRIBUTE_POINTS, 5)
            .set(AGENTS.STRENGTH, 5)
            .set(AGENTS.DEXTERITY, 5)
            .set(AGENTS.CONSTITUTION, 5)
            .set(AGENTS.PERCEPTION, 5)
            .set(AGENTS.INTELLIGENCE, 5)
            .set(AGENTS.LUCK, 5)
            .execute()
        return id
    }
}
