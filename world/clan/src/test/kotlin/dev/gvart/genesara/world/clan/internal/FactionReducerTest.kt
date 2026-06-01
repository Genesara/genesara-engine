package dev.gvart.genesara.world.clan.internal

import com.zaxxer.hikari.HikariDataSource
import dev.gvart.genesara.account.PlayerId
import dev.gvart.genesara.player.Agent
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.player.FactionRank
import dev.gvart.genesara.world.Biome
import dev.gvart.genesara.world.ClanId
import dev.gvart.genesara.world.ClanRank
import dev.gvart.genesara.world.Climate
import dev.gvart.genesara.world.CreateClanOutcome
import dev.gvart.genesara.world.FactionInvite
import dev.gvart.genesara.world.FactionInviteId
import dev.gvart.genesara.world.FactionInviteStore
import dev.gvart.genesara.world.Gauge
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.ResourceSpawnRule
import dev.gvart.genesara.world.Terrain
import dev.gvart.genesara.world.WorldRejection
import dev.gvart.genesara.world.commands.ClanCommand
import dev.gvart.genesara.world.commands.FactionCommand
import dev.gvart.genesara.world.events.ClanEvent
import dev.gvart.genesara.world.events.FactionEvent
import dev.gvart.genesara.world.internal.balance.BalanceLookup
import dev.gvart.genesara.world.internal.jooq.tables.references.CLANS
import dev.gvart.genesara.world.internal.jooq.tables.references.CLAN_MEMBERS
import dev.gvart.genesara.world.internal.jooq.tables.references.FACTIONS
import dev.gvart.genesara.world.internal.testsupport.WorldFlyway
import dev.gvart.genesara.world.internal.worldstate.slices.CoreSlice
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
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration test for the faction reducers against the real [JooqClanRegistry] +
 * [JooqFactionRegistry] (world-core schema via Testcontainers). A [RecordingAgents] stands in for
 * the `:player` side so the test can assert the cross-module `faction_rank` mirror — the bridge
 * that activates the skill-slot bonus. Invite store is in-memory; clan formation is ungated so the
 * [CoreSlice] is empty.
 */
@Testcontainers
class FactionReducerTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("faction_it")
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

    private val core = CoreSlice.EMPTY
    private val clans = JooqClanRegistry(dsl)
    private val factions = JooqFactionRegistry(dsl)
    private lateinit var agents: RecordingAgents
    private lateinit var factionInvites: FakeFactionInviteStore
    private val balance = StubBalance
    private val tickIntervalSeconds = 5L

    @BeforeEach
    fun reset() {
        dsl.truncate(CLAN_MEMBERS).cascade().execute()
        dsl.truncate(CLANS).cascade().execute()
        dsl.truncate(FACTIONS).cascade().execute()
        agents = RecordingAgents()
        factionInvites = FakeFactionInviteStore()
    }

    @Test
    fun `createFaction makes the founding Archon Sovereign, the rest Pact, and mirrors every rank to player`() {
        val (archon, clanId) = clanWith("Vanguards", member = true)
        val member = clans.roster(clanId).first { it.clanRank != ClanRank.ARCHON }.agentId

        val out = assertNotNull(
            reduceCreateFaction(core, FactionCommand.CreateFaction(archon, "Concord"), clans, factions, agents, tick = 10).getOrNull(),
        )
        assertIs<FactionEvent.FactionFormed>(out.events.single())
        assertEquals(FactionRank.SOVEREIGN, clans.clanOf(archon)!!.factionRank)
        assertEquals(FactionRank.PACT, clans.clanOf(member)!!.factionRank)
        // The :player mirror fired for both members — this is what activates the slot bonus.
        assertEquals(FactionRank.SOVEREIGN, agents.lastRank(archon))
        assertEquals(FactionRank.PACT, agents.lastRank(member))
    }

    @Test
    fun `createFaction by a non-Archon is rejected`() {
        val (_, clanId) = clanWith("Hierarchy", member = true)
        val member = clans.roster(clanId).first { it.clanRank != ClanRank.ARCHON }.agentId
        val rejection = reduceCreateFaction(core, FactionCommand.CreateFaction(member, "X"), clans, factions, agents, tick = 1).leftOrNull()
        assertIs<WorldRejection.NotClanArchon>(assertNotNull(rejection))
    }

    @Test
    fun `createFaction rejects a clan already in a faction`() {
        val (archon, _) = clanWith("Founders")
        reduceCreateFaction(core, FactionCommand.CreateFaction(archon, "First"), clans, factions, agents, tick = 1)
        val rejection = reduceCreateFaction(core, FactionCommand.CreateFaction(archon, "Second"), clans, factions, agents, tick = 2).leftOrNull()
        assertIs<WorldRejection.AlreadyInFaction>(assertNotNull(rejection))
    }

    @Test
    fun `a second clan joins via invite then accept, with ranks mirrored`() {
        val (sovereign, _) = clanWith("Alpha")
        reduceCreateFaction(core, FactionCommand.CreateFaction(sovereign, "League"), clans, factions, agents, tick = 1)
        val (bobArchon, bobClan) = clanWith("Beta")

        val invite = reduceInviteClanToFaction(core, FactionCommand.InviteClanToFaction(sovereign, bobClan), clans, factionInvites, balance, tickIntervalSeconds, tick = 2).getOrNull()
        val received = assertIs<FactionEvent.FactionInviteReceived>(assertNotNull(invite).events.single())
        assertTrue(bobArchon in received.listeners)

        val accept = reduceRespondFactionInvite(core, FactionCommand.RespondFactionInvite(bobArchon, received.inviteId.value, accept = true), clans, factions, factionInvites, agents, tick = 3).getOrNull()
        assertIs<FactionEvent.FactionJoined>(assertNotNull(accept).events.single())
        assertEquals(FactionRank.PACT, clans.clanOf(bobArchon)!!.factionRank)
        assertEquals(FactionRank.PACT, agents.lastRank(bobArchon))
    }

    @Test
    fun `invite below Pillar is rejected`() {
        val (sovereign, _) = clanWith("Gamma")
        reduceCreateFaction(core, FactionCommand.CreateFaction(sovereign, "Bloc"), clans, factions, agents, tick = 1)
        val (_, otherClan) = clanWith("Delta")
        // sovereign promotes nobody; a Pact member can't invite. Add a Pact member to sovereign's clan:
        val sovereignClan = clans.clanOf(sovereign)!!.clan.id
        val pact = agent()
        clans.addMember(sovereignClan, pact, ClanRank.SWORN, tick = 2)
        factions.setFactionRank(pact, FactionRank.PACT)
        val rejection = reduceInviteClanToFaction(core, FactionCommand.InviteClanToFaction(pact, otherClan), clans, factionInvites, balance, tickIntervalSeconds, tick = 3).leftOrNull()
        assertIs<WorldRejection.InsufficientFactionRank>(assertNotNull(rejection))
    }

    @Test
    fun `responding to someone else's faction invite is rejected`() {
        val (sovereign, _) = clanWith("Eps")
        reduceCreateFaction(core, FactionCommand.CreateFaction(sovereign, "Pact1"), clans, factions, agents, tick = 1)
        val (_, targetClan) = clanWith("Zeta")
        val invite = reduceInviteClanToFaction(core, FactionCommand.InviteClanToFaction(sovereign, targetClan), clans, factionInvites, balance, tickIntervalSeconds, tick = 2).getOrNull()
        val inviteId = (assertNotNull(invite).events.single() as FactionEvent.FactionInviteReceived).inviteId
        // The sovereign is in a clan (Eps) but not the invited clan (Zeta), so they are not the invitee.
        val rejection = reduceRespondFactionInvite(core, FactionCommand.RespondFactionInvite(sovereign, inviteId.value, accept = true), clans, factions, factionInvites, agents, tick = 3).leftOrNull()
        assertIs<WorldRejection.NotFactionInvitee>(assertNotNull(rejection))
    }

    @Test
    fun `leaving a faction clears ranks and dissolves the faction when the last clan leaves`() {
        val (sovereign, _) = clanWith("Solo")
        reduceCreateFaction(core, FactionCommand.CreateFaction(sovereign, "Lonely"), clans, factions, agents, tick = 1)

        val out = assertNotNull(reduceLeaveFaction(core, FactionCommand.LeaveFaction(sovereign), clans, factions, agents, tick = 5).getOrNull())
        assertTrue(out.events.any { it is FactionEvent.FactionLeft })
        assertTrue(out.events.any { it is FactionEvent.FactionDissolved })
        assertNull(clans.clanOf(sovereign)!!.factionRank)
        assertNull(agents.lastRank(sovereign))
    }

    @Test
    fun `faction promote raises a member one rank and mirrors it`() {
        val (sovereign, _) = clanWith("Theta")
        reduceCreateFaction(core, FactionCommand.CreateFaction(sovereign, "Order"), clans, factions, agents, tick = 1)
        val member = joinSecondClan(sovereign)

        val out = assertNotNull(reducePromoteFactionMember(core, FactionCommand.PromoteFactionMember(sovereign, member), clans, factions, agents, tick = 5).getOrNull())
        val changed = assertIs<FactionEvent.FactionRankChanged>(out.events.single())
        assertEquals(FactionRank.PACT, changed.previousRank)
        assertEquals(FactionRank.SPEAKER, changed.newRank)
        assertEquals(FactionRank.SPEAKER, agents.lastRank(member))
    }

    @Test
    fun `faction promote by a non-Sovereign is rejected`() {
        val (sovereign, _) = clanWith("Iota")
        reduceCreateFaction(core, FactionCommand.CreateFaction(sovereign, "Ring"), clans, factions, agents, tick = 1)
        val member = joinSecondClan(sovereign)
        val rejection = reducePromoteFactionMember(core, FactionCommand.PromoteFactionMember(member, sovereign), clans, factions, agents, tick = 5).leftOrNull()
        assertIs<WorldRejection.InsufficientFactionRank>(assertNotNull(rejection))
    }

    @Test
    fun `faction demote below Pact is rejected`() {
        val (sovereign, _) = clanWith("Kappa")
        reduceCreateFaction(core, FactionCommand.CreateFaction(sovereign, "Floor"), clans, factions, agents, tick = 1)
        val member = joinSecondClan(sovereign)
        val rejection = reduceDemoteFactionMember(core, FactionCommand.DemoteFactionMember(sovereign, member), clans, factions, agents, tick = 5).leftOrNull()
        assertIs<WorldRejection.InvalidFactionRankAction>(assertNotNull(rejection))
    }

    // ─────────── cross-slice: clan dissolve/kick must clear the faction mirror (review #1/#2) ───────────

    @Test
    fun `dissolving a clan in a faction clears every member's player mirror and dissolves the now-empty faction`() {
        val (sovereign, clanId) = clanWith("Doomed", member = true)
        val member = clans.roster(clanId).first { it.clanRank != ClanRank.ARCHON }.agentId
        reduceCreateFaction(core, FactionCommand.CreateFaction(sovereign, "Ephemeral"), clans, factions, agents, tick = 1)
        val factionId = clans.clanOf(sovereign)!!.clan.factionId!!
        assertEquals(FactionRank.SOVEREIGN, agents.lastRank(sovereign))
        assertEquals(FactionRank.PACT, agents.lastRank(member))

        val out = assertNotNull(reduceDissolveClan(core, ClanCommand.DissolveClan(sovereign), clans, factions, agents, tick = 5).getOrNull())

        assertNull(agents.lastRank(sovereign), "dissolved-clan member must lose the faction-rank mirror")
        assertNull(agents.lastRank(member))
        assertNull(factions.findFaction(factionId), "faction with no remaining clans must be deleted")
        assertTrue(out.events.any { it is FactionEvent.FactionDissolved })
        assertTrue(out.events.any { it is ClanEvent.ClanDissolved })
        assertNull(clans.findClan(clanId))
    }

    @Test
    fun `kicking a faction member clears their mirror but keeps the faction`() {
        val (sovereign, clanId) = clanWith("Standing", member = true)
        val member = clans.roster(clanId).first { it.clanRank != ClanRank.ARCHON }.agentId
        reduceCreateFaction(core, FactionCommand.CreateFaction(sovereign, "Enduring"), clans, factions, agents, tick = 1)
        val factionId = clans.clanOf(sovereign)!!.clan.factionId!!

        reduceKickClanMember(core, ClanCommand.KickClanMember(sovereign, member), clans, agents, tick = 5)

        assertNull(agents.lastRank(member), "kicked member must lose the faction-rank mirror")
        assertNotNull(factions.findFaction(factionId), "faction survives — the clan is still in it")
        assertEquals(FactionRank.SOVEREIGN, agents.lastRank(sovereign))
    }

    @Test
    fun `dissolving one clan of a multi-clan faction clears only that clan and keeps the faction`() {
        val (sovereign, _) = clanWith("Primary")
        reduceCreateFaction(core, FactionCommand.CreateFaction(sovereign, "Coalition"), clans, factions, agents, tick = 1)
        val factionId = clans.clanOf(sovereign)!!.clan.factionId!!
        val secondArchon = joinSecondClan(sovereign)
        val secondClan = clans.clanOf(secondArchon)!!.clan.id

        reduceDissolveClan(core, ClanCommand.DissolveClan(secondArchon), clans, factions, agents, tick = 5)

        assertNull(agents.lastRank(secondArchon), "dissolved clan's member loses the mirror")
        assertNotNull(factions.findFaction(factionId), "faction survives — the primary clan remains")
        assertEquals(FactionRank.SOVEREIGN, agents.lastRank(sovereign))
        assertNull(clans.findClan(secondClan))
    }

    /** Founds a clan; returns (archon, clanId). When [member] is true, also adds one Sworn member. */
    private fun clanWith(name: String, member: Boolean = false): Pair<AgentId, ClanId> {
        val archon = agent()
        val clanId = (clans.createClan(name, archon, tick = 1) as CreateClanOutcome.Created).clan.id
        if (member) clans.addMember(clanId, agent(), ClanRank.SWORN, tick = 1)
        return archon to clanId
    }

    /** Creates a second clan, invites + accepts it into [sovereign]'s faction, returns that clan's Archon (now Pact). */
    private fun joinSecondClan(sovereign: AgentId): AgentId {
        val (archon, clanId) = clanWith("clan-${UUID.randomUUID().toString().take(6)}")
        val invite = reduceInviteClanToFaction(core, FactionCommand.InviteClanToFaction(sovereign, clanId), clans, factionInvites, balance, tickIntervalSeconds, tick = 2).getOrNull()!!
        val inviteId = (invite.events.single() as FactionEvent.FactionInviteReceived).inviteId
        reduceRespondFactionInvite(core, FactionCommand.RespondFactionInvite(archon, inviteId.value, accept = true), clans, factions, factionInvites, agents, tick = 3)
        return archon
    }

    private fun agent(): AgentId = AgentId(UUID.randomUUID())

    private class RecordingAgents : AgentRegistry {
        private val ranks = mutableMapOf<AgentId, FactionRank?>()
        fun lastRank(agentId: AgentId): FactionRank? = ranks[agentId]
        override fun setFactionRank(agentId: AgentId, rank: FactionRank?): Boolean {
            ranks[agentId] = rank
            return true
        }
        override fun find(id: AgentId): Agent? = null
        override fun listForOwner(owner: PlayerId): List<Agent> = emptyList()
    }

    private class FakeFactionInviteStore : FactionInviteStore {
        private val invites = mutableMapOf<FactionInviteId, FactionInvite>()
        override fun create(invite: FactionInvite, ttlSeconds: Long) { invites[invite.inviteId] = invite }
        override fun find(inviteId: FactionInviteId): FactionInvite? = invites[inviteId]
        override fun findByTargetClan(targetClanId: ClanId): List<FactionInvite> = invites.values.filter { it.targetClanId == targetClanId }
        override fun delete(inviteId: FactionInviteId) { invites.remove(inviteId) }
    }

    private object StubBalance : BalanceLookup {
        override fun moveStaminaCost(biome: Biome, climate: Climate, terrain: Terrain): Int = error("unused")
        override fun staminaRegenPerTick(climate: Climate): Int = error("unused")
        override fun resourceSpawnsFor(terrain: Terrain): List<ResourceSpawnRule> = error("unused")
        override fun harvestStaminaCost(item: ItemId): Int = error("unused")
        override fun harvestYield(item: ItemId): Int = error("unused")
        override fun gaugeDrainPerTick(gauge: Gauge): Int = error("unused")
        override fun gaugeLowThreshold(gauge: Gauge): Int = error("unused")
        override fun starvationDamagePerTick(): Int = error("unused")
        override fun isWaterSource(terrain: Terrain): Boolean = error("unused")
        override fun drinkStaminaCost(): Int = error("unused")
        override fun drinkThirstRefill(): Int = error("unused")
        override fun sleepRegenPerOfflineTick(): Int = error("unused")
        override fun isTraversable(terrain: Terrain): Boolean = error("unused")
    }
}
