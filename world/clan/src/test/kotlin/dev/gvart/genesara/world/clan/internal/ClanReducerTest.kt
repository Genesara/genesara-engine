package dev.gvart.genesara.world.clan.internal

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.AddMemberOutcome
import dev.gvart.genesara.world.Biome
import dev.gvart.genesara.world.Clan
import dev.gvart.genesara.world.ClanId
import dev.gvart.genesara.world.ClanInvite
import dev.gvart.genesara.world.ClanInviteId
import dev.gvart.genesara.world.ClanInviteStore
import dev.gvart.genesara.world.ClanMember
import dev.gvart.genesara.world.ClanMembership
import dev.gvart.genesara.world.ClanRank
import dev.gvart.genesara.world.ClanRegistry
import dev.gvart.genesara.world.Climate
import dev.gvart.genesara.world.CreateClanOutcome
import dev.gvart.genesara.world.Gauge
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.ResourceSpawnRule
import dev.gvart.genesara.world.Terrain
import dev.gvart.genesara.world.WorldRejection
import dev.gvart.genesara.world.commands.ClanCommand
import dev.gvart.genesara.world.events.ClanEvent
import dev.gvart.genesara.world.internal.balance.BalanceLookup
import dev.gvart.genesara.world.internal.worldstate.slices.CoreSlice
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

/**
 * Logic-level tests for the clan lifecycle reducers against an in-memory [ClanRegistry]
 * (the Jooq impl is covered by [JooqClanRegistryIntegrationTest]). Clan formation is
 * ungated, so the reducers ignore the [CoreSlice] — [CoreSlice.EMPTY] is passed throughout.
 */
class ClanReducerTest {

    private val founder = AgentId(UUID.randomUUID())
    private val alice = AgentId(UUID.randomUUID())
    private val bob = AgentId(UUID.randomUUID())
    private val core = CoreSlice.EMPTY
    private val clans = FakeClanRegistry()
    private val clanInvites = FakeClanInviteStore()
    private val balance = StubBalance
    private val tickIntervalSeconds = 5L
    // These fakes' clans are never in a faction, so leave/dissolve/kick skip the faction-cleanup
    // branch — the faction-mirror behaviour is covered against real registries in FactionReducerTest.
    private val factions = dev.gvart.genesara.world.internal.testsupport.NoOpFactionRegistry
    private val noOpAgents = object : dev.gvart.genesara.player.AgentRegistry {
        override fun find(id: dev.gvart.genesara.player.AgentId): dev.gvart.genesara.player.Agent? = null
        override fun listForOwner(owner: dev.gvart.genesara.account.PlayerId): List<dev.gvart.genesara.player.Agent> = emptyList()
    }

    @Test
    fun `createClan founds the clan and emits ClanJoined for the founding Archon`() {
        val out = assertNotNull(
            reduceCreateClan(core, ClanCommand.CreateClan(founder, "Ashen Pact"), clans, tick = 100).getOrNull(),
        )
        val joined = assertIs<ClanEvent.ClanJoined>(out.events.single())
        assertEquals(founder, joined.agent)
        assertEquals(ClanRank.ARCHON, joined.clanRank)
        assertEquals(setOf(founder), joined.listeners)
        assertEquals(ClanRank.ARCHON, clans.clanOf(founder)!!.clanRank)
    }

    @Test
    fun `createClan rejects a duplicate name`() {
        reduceCreateClan(core, ClanCommand.CreateClan(founder, "Ashen Pact"), clans, tick = 1)
        val rejection = reduceCreateClan(core, ClanCommand.CreateClan(alice, "Ashen Pact"), clans, tick = 2).leftOrNull()
        assertIs<WorldRejection.ClanNameTaken>(assertNotNull(rejection))
    }

    @Test
    fun `createClan rejects a founder already in a clan`() {
        reduceCreateClan(core, ClanCommand.CreateClan(founder, "First"), clans, tick = 1)
        val rejection = reduceCreateClan(core, ClanCommand.CreateClan(founder, "Second"), clans, tick = 2).leftOrNull()
        assertIs<WorldRejection.AlreadyInClan>(assertNotNull(rejection))
    }

    @Test
    fun `leaveClan by a non-Archon emits ClanLeft and removes the member`() {
        val clanId = found("Roster")
        clans.addMember(clanId, alice, ClanRank.SWORN, tick = 2)

        val out = assertNotNull(reduceLeaveClan(core, ClanCommand.LeaveClan(alice), clans, factions, noOpAgents, tick = 3).getOrNull())
        val left = assertIs<ClanEvent.ClanLeft>(out.events.single())
        assertEquals(alice, left.agent)
        assertEquals(ClanEvent.ClanLeft.Reason.LEFT, left.reason)
        assertEquals(setOf(founder, alice), left.listeners)
        assertNull(clans.clanOf(alice))
    }

    @Test
    fun `leaveClan by the sole Archon as last member dissolves the clan`() {
        found("Solo")
        val out = assertNotNull(reduceLeaveClan(core, ClanCommand.LeaveClan(founder), clans, factions, noOpAgents, tick = 5).getOrNull())
        assertIs<ClanEvent.ClanDissolved>(out.events.single())
        assertNull(clans.clanOf(founder))
    }

    @Test
    fun `leaveClan by the sole Archon with members is rejected — must hand off first`() {
        val clanId = found("Held")
        clans.addMember(clanId, alice, ClanRank.SWORN, tick = 2)
        val rejection = reduceLeaveClan(core, ClanCommand.LeaveClan(founder), clans, factions, noOpAgents, tick = 3).leftOrNull()
        assertIs<WorldRejection.MustHandOffLeadership>(assertNotNull(rejection))
        assertEquals(ClanRank.ARCHON, clans.clanOf(founder)!!.clanRank)
    }

    @Test
    fun `leaveClan by an agent in no clan is rejected`() {
        val rejection = reduceLeaveClan(core, ClanCommand.LeaveClan(alice), clans, factions, noOpAgents, tick = 1).leftOrNull()
        assertIs<WorldRejection.NotInAnyClan>(assertNotNull(rejection))
    }

    @Test
    fun `dissolveClan by the Archon dissolves and notifies all former members`() {
        val clanId = found("Doomed")
        clans.addMember(clanId, alice, ClanRank.SWORN, tick = 2)

        val out = assertNotNull(reduceDissolveClan(core, ClanCommand.DissolveClan(founder), clans, factions, noOpAgents, tick = 3).getOrNull())
        val dissolved = assertIs<ClanEvent.ClanDissolved>(out.events.single())
        assertEquals(setOf(founder, alice), dissolved.listeners)
        assertNull(clans.findClan(clanId))
    }

    @Test
    fun `dissolveClan by a non-Archon is rejected`() {
        val clanId = found("Guarded")
        clans.addMember(clanId, alice, ClanRank.VANGUARD, tick = 2)
        val rejection = reduceDissolveClan(core, ClanCommand.DissolveClan(alice), clans, factions, noOpAgents, tick = 3).leftOrNull()
        assertIs<WorldRejection.NotClanArchon>(assertNotNull(rejection))
    }

    @Test
    fun `transferLeadership promotes the target to Archon and steps the caller down to Vanguard`() {
        val clanId = found("Succession")
        clans.addMember(clanId, alice, ClanRank.BOUND, tick = 2)

        val out = assertNotNull(
            reduceTransferClanLeadership(core, ClanCommand.TransferClanLeadership(founder, alice), clans, tick = 3).getOrNull(),
        )
        val changes = out.events.filterIsInstance<ClanEvent.RankChanged>()
        assertEquals(2, changes.size)
        assertEquals(ClanRank.VANGUARD, clans.clanOf(founder)!!.clanRank)
        assertEquals(ClanRank.ARCHON, clans.clanOf(alice)!!.clanRank)
    }

    @Test
    fun `transferLeadership by a non-Archon is rejected`() {
        val clanId = found("Locked")
        clans.addMember(clanId, alice, ClanRank.VANGUARD, tick = 2)
        val rejection = reduceTransferClanLeadership(
            core, ClanCommand.TransferClanLeadership(alice, founder), clans, tick = 3,
        ).leftOrNull()
        assertIs<WorldRejection.NotClanArchon>(assertNotNull(rejection))
    }

    @Test
    fun `transferLeadership to yourself is rejected`() {
        found("Selfish")
        val rejection = reduceTransferClanLeadership(
            core, ClanCommand.TransferClanLeadership(founder, founder), clans, tick = 3,
        ).leftOrNull()
        assertIs<WorldRejection.CannotTransferToSelf>(assertNotNull(rejection))
    }

    @Test
    fun `transferLeadership to a non-member is rejected`() {
        found("Insular")
        val rejection = reduceTransferClanLeadership(
            core, ClanCommand.TransferClanLeadership(founder, bob), clans, tick = 3,
        ).leftOrNull()
        assertIs<WorldRejection.TransferTargetNotClanMember>(assertNotNull(rejection))
    }

    // ─────────────────────── invite / respond ───────────────────────

    @Test
    fun `invite emits ClanInviteReceived to the invitee and stores the invite`() {
        val clanId = found("Recruiters")
        val out = assertNotNull(
            reduceClanInvite(core, ClanCommand.InviteToClan(founder, alice), clans, clanInvites, balance, tickIntervalSeconds, tick = 10).getOrNull(),
        )
        val received = assertIs<ClanEvent.ClanInviteReceived>(out.events.single())
        assertEquals(alice, received.invitee)
        assertEquals(setOf(alice), received.listeners)
        assertEquals(1, clanInvites.findByClan(clanId).size)
    }

    @Test
    fun `invite below Bound is rejected`() {
        val clanId = found("Strict")
        clans.addMember(clanId, alice, ClanRank.SWORN, tick = 2)
        val rejection = reduceClanInvite(core, ClanCommand.InviteToClan(alice, bob), clans, clanInvites, balance, tickIntervalSeconds, tick = 3).leftOrNull()
        assertIs<WorldRejection.InsufficientClanRank>(assertNotNull(rejection))
    }

    @Test
    fun `invite of an agent already in a clan is rejected`() {
        found("First")
        val clans2Founder = alice
        reduceCreateClan(core, ClanCommand.CreateClan(clans2Founder, "Second"), clans, tick = 1)
        val rejection = reduceClanInvite(core, ClanCommand.InviteToClan(founder, alice), clans, clanInvites, balance, tickIntervalSeconds, tick = 5).leftOrNull()
        assertIs<WorldRejection.InviteeAlreadyInClan>(assertNotNull(rejection))
    }

    @Test
    fun `invite is rejected when it would exceed the cap`() {
        // StubBalance cap = 2; founder (1 member) + 1 pending = cap, a second invite overflows.
        found("Capped")
        reduceClanInvite(core, ClanCommand.InviteToClan(founder, alice), clans, clanInvites, balance, tickIntervalSeconds, tick = 1)
        val rejection = reduceClanInvite(core, ClanCommand.InviteToClan(founder, bob), clans, clanInvites, balance, tickIntervalSeconds, tick = 2).leftOrNull()
        assertIs<WorldRejection.ClanFull>(assertNotNull(rejection))
    }

    @Test
    fun `re-inviting the same agent refreshes without double-counting the cap`() {
        val clanId = found("Patient")
        reduceClanInvite(core, ClanCommand.InviteToClan(founder, alice), clans, clanInvites, balance, tickIntervalSeconds, tick = 1)
        val out = assertNotNull(
            reduceClanInvite(core, ClanCommand.InviteToClan(founder, alice), clans, clanInvites, balance, tickIntervalSeconds, tick = 9).getOrNull(),
        )
        assertIs<ClanEvent.ClanInviteReceived>(out.events.single())
        assertEquals(1, clanInvites.findByClan(clanId).size)
    }

    @Test
    fun `accepting an invite joins the clan as an Initiate and emits ClanJoined`() {
        found("Welcoming")
        val inviteId = sendInvite(founder, alice)
        val out = assertNotNull(
            reduceRespondClanInvite(core, ClanCommand.RespondClanInvite(alice, inviteId.value, accept = true), clans, clanInvites, balance, tick = 5).getOrNull(),
        )
        val joined = assertIs<ClanEvent.ClanJoined>(out.events.single())
        assertEquals(alice, joined.agent)
        assertEquals(ClanRank.INITIATE, joined.clanRank)
        assertEquals(ClanRank.INITIATE, clans.clanOf(alice)!!.clanRank)
        assertNull(clanInvites.find(inviteId))
    }

    @Test
    fun `declining an invite emits ClanInviteDeclined and does not join`() {
        found("Spurned")
        val inviteId = sendInvite(founder, alice)
        val out = assertNotNull(
            reduceRespondClanInvite(core, ClanCommand.RespondClanInvite(alice, inviteId.value, accept = false), clans, clanInvites, balance, tick = 5).getOrNull(),
        )
        assertIs<ClanEvent.ClanInviteDeclined>(out.events.single())
        assertNull(clans.clanOf(alice))
        assertNull(clanInvites.find(inviteId))
    }

    @Test
    fun `responding to an unknown invite is rejected`() {
        val rejection = reduceRespondClanInvite(core, ClanCommand.RespondClanInvite(alice, UUID.randomUUID(), accept = true), clans, clanInvites, balance, tick = 1).leftOrNull()
        assertIs<WorldRejection.ClanInviteNotFound>(assertNotNull(rejection))
    }

    @Test
    fun `responding to someone else's invite is rejected`() {
        found("Private")
        val inviteId = sendInvite(founder, alice)
        val rejection = reduceRespondClanInvite(core, ClanCommand.RespondClanInvite(bob, inviteId.value, accept = true), clans, clanInvites, balance, tick = 5).leftOrNull()
        assertIs<WorldRejection.NotClanInvitee>(assertNotNull(rejection))
    }

    @Test
    fun `accepting after joining another clan voids the invite`() {
        found("Origin")
        val inviteId = sendInvite(founder, alice)
        reduceCreateClan(core, ClanCommand.CreateClan(alice, "Alices Own"), clans, tick = 4)
        val rejection = reduceRespondClanInvite(core, ClanCommand.RespondClanInvite(alice, inviteId.value, accept = true), clans, clanInvites, balance, tick = 5).leftOrNull()
        assertIs<WorldRejection.ClanInviteVoid>(assertNotNull(rejection))
    }

    // ─────────────────────── kick ───────────────────────

    @Test
    fun `kick removes a lower-ranked member and emits ClanLeft KICKED`() {
        val clanId = found("Disciplined")
        clans.addMember(clanId, alice, ClanRank.SWORN, tick = 2)
        val out = assertNotNull(reduceKickClanMember(core, ClanCommand.KickClanMember(founder, alice), clans, noOpAgents, tick = 3).getOrNull())
        val left = assertIs<ClanEvent.ClanLeft>(out.events.single())
        assertEquals(alice, left.agent)
        assertEquals(ClanEvent.ClanLeft.Reason.KICKED, left.reason)
        assertNull(clans.clanOf(alice))
    }

    @Test
    fun `kicking yourself is rejected`() {
        found("SelfAware")
        val rejection = reduceKickClanMember(core, ClanCommand.KickClanMember(founder, founder), clans, noOpAgents, tick = 3).leftOrNull()
        assertIs<WorldRejection.CannotKickSelfFromClan>(assertNotNull(rejection))
    }

    @Test
    fun `kick below Vanguard is rejected`() {
        val clanId = found("Hierarchy")
        clans.addMember(clanId, alice, ClanRank.SWORN, tick = 2)
        clans.addMember(clanId, bob, ClanRank.INITIATE, tick = 3)
        val rejection = reduceKickClanMember(core, ClanCommand.KickClanMember(alice, bob), clans, noOpAgents, tick = 4).leftOrNull()
        assertIs<WorldRejection.InsufficientClanRank>(assertNotNull(rejection))
    }

    @Test
    fun `kick of a peer is rejected`() {
        val clanId = found("Peers")
        clans.addMember(clanId, alice, ClanRank.VANGUARD, tick = 2)
        clans.addMember(clanId, bob, ClanRank.VANGUARD, tick = 3)
        val rejection = reduceKickClanMember(core, ClanCommand.KickClanMember(alice, bob), clans, noOpAgents, tick = 4).leftOrNull()
        assertIs<WorldRejection.InvalidClanRankAction>(assertNotNull(rejection))
    }

    // ─────────────────────── promote / demote ───────────────────────

    @Test
    fun `promote raises the member one rank`() {
        val clanId = found("Ladder")
        clans.addMember(clanId, alice, ClanRank.SWORN, tick = 2)
        val out = assertNotNull(reducePromoteClanMember(core, ClanCommand.PromoteClanMember(founder, alice), clans, tick = 3).getOrNull())
        val changed = assertIs<ClanEvent.RankChanged>(out.events.single())
        assertEquals(ClanRank.SWORN, changed.previousRank)
        assertEquals(ClanRank.BOUND, changed.newRank)
        assertEquals(ClanRank.BOUND, clans.clanOf(alice)!!.clanRank)
    }

    @Test
    fun `promote that would mint an Archon is rejected`() {
        val clanId = found("Ceiling")
        clans.addMember(clanId, alice, ClanRank.VANGUARD, tick = 2)
        val rejection = reducePromoteClanMember(core, ClanCommand.PromoteClanMember(founder, alice), clans, tick = 3).leftOrNull()
        assertIs<WorldRejection.InvalidClanRankAction>(assertNotNull(rejection))
    }

    @Test
    fun `promote to at-or-above the actor's rank is rejected`() {
        val clanId = found("Guarded2")
        clans.addMember(clanId, alice, ClanRank.VANGUARD, tick = 2)
        clans.addMember(clanId, bob, ClanRank.BOUND, tick = 3)
        // alice (Vanguard) promoting bob Bound→Vanguard: resulting rank not below alice's.
        val rejection = reducePromoteClanMember(core, ClanCommand.PromoteClanMember(alice, bob), clans, tick = 4).leftOrNull()
        assertIs<WorldRejection.InvalidClanRankAction>(assertNotNull(rejection))
    }

    @Test
    fun `demote lowers the member one rank`() {
        val clanId = found("Descent")
        clans.addMember(clanId, alice, ClanRank.BOUND, tick = 2)
        val out = assertNotNull(reduceDemoteClanMember(core, ClanCommand.DemoteClanMember(founder, alice), clans, tick = 3).getOrNull())
        val changed = assertIs<ClanEvent.RankChanged>(out.events.single())
        assertEquals(ClanRank.BOUND, changed.previousRank)
        assertEquals(ClanRank.SWORN, changed.newRank)
    }

    @Test
    fun `demote below Initiate is rejected`() {
        val clanId = found("Floor")
        clans.addMember(clanId, alice, ClanRank.INITIATE, tick = 2)
        val rejection = reduceDemoteClanMember(core, ClanCommand.DemoteClanMember(founder, alice), clans, tick = 3).leftOrNull()
        assertIs<WorldRejection.InvalidClanRankAction>(assertNotNull(rejection))
    }

    private fun sendInvite(inviter: AgentId, invitee: AgentId): ClanInviteId {
        val out = reduceClanInvite(core, ClanCommand.InviteToClan(inviter, invitee), clans, clanInvites, balance, tickIntervalSeconds, tick = 1).getOrNull()
            ?: error("invite setup failed")
        return (out.events.single() as ClanEvent.ClanInviteReceived).inviteId
    }

    private fun found(name: String): ClanId =
        (reduceCreateClan(core, ClanCommand.CreateClan(founder, name), clans, tick = 1).getOrNull()
            ?: error("setup create failed"))
            .let { clans.clanOf(founder)!!.clan.id }

    private class FakeClanRegistry : ClanRegistry {
        private val clans = mutableMapOf<ClanId, Clan>()
        private val members = mutableMapOf<ClanId, MutableList<ClanMember>>()
        private val agentToClan = mutableMapOf<AgentId, ClanId>()

        override fun createClan(name: String, founder: AgentId, tick: Long): CreateClanOutcome {
            agentToClan[founder]?.let { return CreateClanOutcome.AlreadyInClan(it) }
            if (clans.values.any { it.name == name }) return CreateClanOutcome.NameTaken
            val id = ClanId(UUID.randomUUID())
            val clan = Clan(id, name, factionId = null, foundedAtTick = tick)
            clans[id] = clan
            members[id] = mutableListOf(ClanMember(id, founder, ClanRank.ARCHON, null, tick))
            agentToClan[founder] = id
            return CreateClanOutcome.Created(clan)
        }

        override fun findClan(clanId: ClanId): Clan? = clans[clanId]

        override fun clanOf(agentId: AgentId): ClanMembership? {
            val cid = agentToClan[agentId] ?: return null
            val m = members.getValue(cid).first { it.agentId == agentId }
            return ClanMembership(clans.getValue(cid), m.clanRank, m.factionRank)
        }

        override fun roster(clanId: ClanId): List<ClanMember> =
            members[clanId]?.sortedBy { it.joinedAtTick } ?: emptyList()

        override fun memberCount(clanId: ClanId): Int = members[clanId]?.size ?: 0

        override fun addMember(clanId: ClanId, agentId: AgentId, rank: ClanRank, tick: Long): AddMemberOutcome {
            if (clans[clanId] == null) return AddMemberOutcome.ClanNotFound
            agentToClan[agentId]?.let { return AddMemberOutcome.AlreadyInClan(it) }
            members.getValue(clanId).add(ClanMember(clanId, agentId, rank, null, tick))
            agentToClan[agentId] = clanId
            return AddMemberOutcome.Added
        }

        override fun changeClanRank(clanId: ClanId, agentId: AgentId, newRank: ClanRank): Boolean {
            val list = members[clanId] ?: return false
            val idx = list.indexOfFirst { it.agentId == agentId }
            if (idx < 0) return false
            list[idx] = list[idx].copy(clanRank = newRank)
            return true
        }

        override fun removeMember(clanId: ClanId, agentId: AgentId): Boolean {
            val list = members[clanId] ?: return false
            val removed = list.removeAll { it.agentId == agentId }
            if (removed) agentToClan.remove(agentId)
            return removed
        }

        override fun dissolve(clanId: ClanId): List<AgentId> {
            val list = members.remove(clanId) ?: return emptyList()
            clans.remove(clanId)
            return list.map { it.agentId }.also { ids -> ids.forEach(agentToClan::remove) }
        }
    }

    private class FakeClanInviteStore : ClanInviteStore {
        private val invites = mutableMapOf<ClanInviteId, ClanInvite>()
        override fun create(invite: ClanInvite, ttlSeconds: Long) { invites[invite.inviteId] = invite }
        override fun find(inviteId: ClanInviteId): ClanInvite? = invites[inviteId]
        override fun findByClan(clanId: ClanId): List<ClanInvite> = invites.values.filter { it.clanId == clanId }
        override fun delete(inviteId: ClanInviteId) { invites.remove(inviteId) }
    }

    /** Inherits the real defaults (clanInviteTtlSeconds=600) but shrinks the cap to 2 for cap tests. */
    private object StubBalance : BalanceLookup {
        override fun baselineClanCapacity(): Int = 2
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
