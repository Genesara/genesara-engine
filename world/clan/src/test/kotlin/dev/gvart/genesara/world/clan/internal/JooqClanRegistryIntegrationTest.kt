package dev.gvart.genesara.world.clan.internal

import com.zaxxer.hikari.HikariDataSource
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.AddMemberOutcome
import dev.gvart.genesara.world.ClanRank
import dev.gvart.genesara.world.CreateClanOutcome
import dev.gvart.genesara.world.internal.jooq.tables.references.CLANS
import dev.gvart.genesara.world.internal.jooq.tables.references.CLAN_MEMBERS
import dev.gvart.genesara.world.internal.jooq.tables.references.FACTIONS
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration test for [JooqClanRegistry] — the persistent clan + membership store
 * (#22 Slice 1). Clan tables live in the world-core schema, so the harness migrates
 * world-core and talks to a bare jOOQ [DSLContext]. The constraint-race `catch`
 * paths are not exercised single-threaded; the pre-check rejection paths are.
 */
@Testcontainers
class JooqClanRegistryIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("clan_it")
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

    private lateinit var registry: JooqClanRegistry

    @BeforeEach
    fun resetState() {
        dsl.truncate(CLAN_MEMBERS).cascade().execute()
        dsl.truncate(CLANS).cascade().execute()
        dsl.truncate(FACTIONS).cascade().execute()
        registry = JooqClanRegistry(dsl)
    }

    @Test
    fun `createClan enrols the founder as Archon and is readable back`() {
        val founder = agent()
        val outcome = registry.createClan("Ashen Pact", founder, tick = 100)

        val created = assertNotNull(outcome as? CreateClanOutcome.Created)
        assertEquals("Ashen Pact", created.clan.name)
        assertNull(created.clan.factionId)
        assertEquals(100, created.clan.foundedAtTick)

        val membership = assertNotNull(registry.clanOf(founder))
        assertEquals(created.clan.id, membership.clan.id)
        assertEquals(ClanRank.ARCHON, membership.clanRank)
        assertNull(membership.factionRank)
        assertEquals(created.clan, assertNotNull(registry.findClan(created.clan.id)))
    }

    @Test
    fun `createClan rejects a duplicate name`() {
        registry.createClan("Ashen Pact", agent(), tick = 1)
        assertEquals(CreateClanOutcome.NameTaken, registry.createClan("Ashen Pact", agent(), tick = 2))
    }

    @Test
    fun `createClan rejects a founder already in a clan`() {
        val founder = agent()
        val first = registry.createClan("First", founder, tick = 1) as CreateClanOutcome.Created
        val outcome = registry.createClan("Second", founder, tick = 2)

        val rejected = assertNotNull(outcome as? CreateClanOutcome.AlreadyInClan)
        assertEquals(first.clan.id, rejected.existing)
    }

    @Test
    fun `clanOf returns null for an agent in no clan`() {
        assertNull(registry.clanOf(agent()))
    }

    @Test
    fun `roster is ordered by join tick and memberCount tracks size`() {
        val founder = agent()
        val clan = (registry.createClan("Roster", founder, tick = 10) as CreateClanOutcome.Created).clan.id
        val second = agent()
        val third = agent()
        registry.addMember(clan, third, ClanRank.INITIATE, tick = 30)
        registry.addMember(clan, second, ClanRank.SWORN, tick = 20)

        val roster = registry.roster(clan)
        assertEquals(listOf(founder, second, third), roster.map { it.agentId })
        assertEquals(listOf(10L, 20L, 30L), roster.map { it.joinedAtTick })
        assertEquals(3, registry.memberCount(clan))
    }

    @Test
    fun `addMember rejects an agent already in another clan`() {
        val a = (registry.createClan("A", agent(), tick = 1) as CreateClanOutcome.Created).clan.id
        val joiner = agent()
        val b = (registry.createClan("B", joiner, tick = 2) as CreateClanOutcome.Created).clan.id

        val outcome = registry.addMember(a, joiner, ClanRank.INITIATE, tick = 3)
        assertEquals(AddMemberOutcome.AlreadyInClan(b), outcome)
    }

    @Test
    fun `addMember rejects an unknown clan`() {
        val outcome = registry.addMember(
            dev.gvart.genesara.world.ClanId(UUID.randomUUID()),
            agent(),
            ClanRank.INITIATE,
            tick = 1,
        )
        assertEquals(AddMemberOutcome.ClanNotFound, outcome)
    }

    @Test
    fun `changeClanRank updates the member rank and reports a missing membership`() {
        val founder = agent()
        val clan = (registry.createClan("Ranks", founder, tick = 1) as CreateClanOutcome.Created).clan.id
        val member = agent()
        registry.addMember(clan, member, ClanRank.INITIATE, tick = 2)

        assertTrue(registry.changeClanRank(clan, member, ClanRank.VANGUARD))
        assertEquals(ClanRank.VANGUARD, registry.clanOf(member)!!.clanRank)
        assertFalse(registry.changeClanRank(clan, agent(), ClanRank.BOUND))
    }

    @Test
    fun `removeMember drops the membership`() {
        val founder = agent()
        val clan = (registry.createClan("Leavers", founder, tick = 1) as CreateClanOutcome.Created).clan.id
        val member = agent()
        registry.addMember(clan, member, ClanRank.INITIATE, tick = 2)

        assertTrue(registry.removeMember(clan, member))
        assertNull(registry.clanOf(member))
        assertEquals(1, registry.memberCount(clan))
        assertFalse(registry.removeMember(clan, member))
    }

    @Test
    fun `dissolve removes the clan, cascades members, and returns former member ids`() {
        val founder = agent()
        val clan = (registry.createClan("Doomed", founder, tick = 1) as CreateClanOutcome.Created).clan.id
        val member = agent()
        registry.addMember(clan, member, ClanRank.SWORN, tick = 2)

        val former = registry.dissolve(clan)
        assertEquals(setOf(founder, member), former.toSet())
        assertNull(registry.findClan(clan))
        assertNull(registry.clanOf(founder))
        assertNull(registry.clanOf(member))
        assertEquals(0, registry.memberCount(clan))
    }

    @Test
    fun `dissolve of an unknown clan returns empty`() {
        assertEquals(emptyList(), registry.dissolve(dev.gvart.genesara.world.ClanId(UUID.randomUUID())))
    }

    private fun agent(): AgentId = AgentId(UUID.randomUUID())
}
