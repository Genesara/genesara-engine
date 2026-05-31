package dev.gvart.genesara.world.clan.internal

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.AddMemberOutcome
import dev.gvart.genesara.world.Clan
import dev.gvart.genesara.world.ClanId
import dev.gvart.genesara.world.ClanMember
import dev.gvart.genesara.world.ClanMembership
import dev.gvart.genesara.world.ClanRank
import dev.gvart.genesara.world.ClanRegistry
import dev.gvart.genesara.world.CreateClanOutcome
import dev.gvart.genesara.world.WorldRejection
import dev.gvart.genesara.world.commands.ClanCommand
import dev.gvart.genesara.world.events.ClanEvent
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

        val out = assertNotNull(reduceLeaveClan(core, ClanCommand.LeaveClan(alice), clans, tick = 3).getOrNull())
        val left = assertIs<ClanEvent.ClanLeft>(out.events.single())
        assertEquals(alice, left.agent)
        assertEquals(ClanEvent.ClanLeft.Reason.LEFT, left.reason)
        assertEquals(setOf(founder, alice), left.listeners)
        assertNull(clans.clanOf(alice))
    }

    @Test
    fun `leaveClan by the sole Archon as last member dissolves the clan`() {
        found("Solo")
        val out = assertNotNull(reduceLeaveClan(core, ClanCommand.LeaveClan(founder), clans, tick = 5).getOrNull())
        assertIs<ClanEvent.ClanDissolved>(out.events.single())
        assertNull(clans.clanOf(founder))
    }

    @Test
    fun `leaveClan by the sole Archon with members is rejected — must hand off first`() {
        val clanId = found("Held")
        clans.addMember(clanId, alice, ClanRank.SWORN, tick = 2)
        val rejection = reduceLeaveClan(core, ClanCommand.LeaveClan(founder), clans, tick = 3).leftOrNull()
        assertIs<WorldRejection.MustHandOffLeadership>(assertNotNull(rejection))
        assertEquals(ClanRank.ARCHON, clans.clanOf(founder)!!.clanRank)
    }

    @Test
    fun `leaveClan by an agent in no clan is rejected`() {
        val rejection = reduceLeaveClan(core, ClanCommand.LeaveClan(alice), clans, tick = 1).leftOrNull()
        assertIs<WorldRejection.NotInAnyClan>(assertNotNull(rejection))
    }

    @Test
    fun `dissolveClan by the Archon dissolves and notifies all former members`() {
        val clanId = found("Doomed")
        clans.addMember(clanId, alice, ClanRank.SWORN, tick = 2)

        val out = assertNotNull(reduceDissolveClan(core, ClanCommand.DissolveClan(founder), clans, tick = 3).getOrNull())
        val dissolved = assertIs<ClanEvent.ClanDissolved>(out.events.single())
        assertEquals(setOf(founder, alice), dissolved.listeners)
        assertNull(clans.findClan(clanId))
    }

    @Test
    fun `dissolveClan by a non-Archon is rejected`() {
        val clanId = found("Guarded")
        clans.addMember(clanId, alice, ClanRank.VANGUARD, tick = 2)
        val rejection = reduceDissolveClan(core, ClanCommand.DissolveClan(alice), clans, tick = 3).leftOrNull()
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
}
