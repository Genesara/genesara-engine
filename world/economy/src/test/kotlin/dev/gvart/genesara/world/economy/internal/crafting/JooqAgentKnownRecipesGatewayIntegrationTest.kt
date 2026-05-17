package dev.gvart.genesara.world.economy.internal.crafting

import com.zaxxer.hikari.HikariDataSource
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.RecipeId
import dev.gvart.genesara.world.RecipeLearnSource
import dev.gvart.genesara.world.internal.jooq.tables.references.AGENT_KNOWN_RECIPES
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
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Testcontainers
class JooqAgentKnownRecipesGatewayIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("known_recipes_it")
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

    private lateinit var gateway: JooqAgentKnownRecipesGateway
    private val agent = AgentId(UUID.randomUUID())
    private val other = AgentId(UUID.randomUUID())
    private val ironSword = RecipeId("IRON_SWORD_LEGENDARY")
    private val healPotion = RecipeId("HEALTH_POTION_GREATER")

    @BeforeEach
    fun reset() {
        dsl.truncate(AGENT_KNOWN_RECIPES).cascade().execute()
        gateway = JooqAgentKnownRecipesGateway(dsl)
    }

    @Test
    fun `recordIfAbsent returns true on first insert and false on duplicate`() {
        assertTrue(gateway.recordIfAbsent(agent, ironSword, RecipeLearnSource.CLASS_PERK, "SMITHING_FORGE_MASTER", tick = 10L))
        assertFalse(gateway.recordIfAbsent(agent, ironSword, RecipeLearnSource.CLASS_PERK, "SMITHING_FORGE_MASTER", tick = 20L))
    }

    @Test
    fun `known returns every recipe rows for the agent`() {
        gateway.recordIfAbsent(agent, ironSword, RecipeLearnSource.CLASS_PERK, "SMITHING_FORGE_MASTER", tick = 1L)
        gateway.recordIfAbsent(agent, healPotion, RecipeLearnSource.ITEM_LEARNED, "ALCHEMY_SCROLL", tick = 2L)

        assertEquals(setOf(ironSword, healPotion), gateway.known(agent))
    }

    @Test
    fun `isKnown is true for inserted rows and false otherwise`() {
        gateway.recordIfAbsent(agent, ironSword, RecipeLearnSource.CLASS_PERK, "SMITHING_FORGE_MASTER", tick = 1L)

        assertTrue(gateway.isKnown(agent, ironSword))
        assertFalse(gateway.isKnown(agent, healPotion))
        assertFalse(gateway.isKnown(other, ironSword))
    }

    @Test
    fun `agents do not see each other's ledgers`() {
        gateway.recordIfAbsent(agent, ironSword, RecipeLearnSource.CLASS_PERK, "SMITHING_FORGE_MASTER", tick = 1L)
        gateway.recordIfAbsent(other, healPotion, RecipeLearnSource.ITEM_LEARNED, "ALCHEMY_SCROLL", tick = 2L)

        assertEquals(setOf(ironSword), gateway.known(agent))
        assertEquals(setOf(healPotion), gateway.known(other))
    }

    @Test
    fun `duplicate recordIfAbsent keeps the original learned_at_tick and source_ref`() {
        gateway.recordIfAbsent(agent, ironSword, RecipeLearnSource.CLASS_PERK, "FIRST_PERK", tick = 10L)
        gateway.recordIfAbsent(agent, ironSword, RecipeLearnSource.CLASS_PERK, "DIFFERENT_PERK", tick = 99L)

        val row = dsl.select(AGENT_KNOWN_RECIPES.LEARNED_AT_TICK, AGENT_KNOWN_RECIPES.SOURCE_REF)
            .from(AGENT_KNOWN_RECIPES)
            .where(AGENT_KNOWN_RECIPES.AGENT_ID.eq(agent.id))
            .and(AGENT_KNOWN_RECIPES.RECIPE_ID.eq(ironSword.value))
            .fetchOne()
        assertEquals(10L, row?.get(AGENT_KNOWN_RECIPES.LEARNED_AT_TICK))
        assertEquals("FIRST_PERK", row?.get(AGENT_KNOWN_RECIPES.SOURCE_REF))
    }
}
