package dev.gvart.genesara.world.internal.testsupport

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.AddMemberOutcome
import dev.gvart.genesara.world.Clan
import dev.gvart.genesara.world.ClanId
import dev.gvart.genesara.world.ClanMember
import dev.gvart.genesara.world.ClanMembership
import dev.gvart.genesara.world.ClanRank
import dev.gvart.genesara.world.ClanRegistry
import dev.gvart.genesara.world.CreateClanOutcome

/** Empty [ClanRegistry] for tests that don't exercise clan behavior. */
object NoOpClanRegistry : ClanRegistry {
    override fun createClan(name: String, founder: AgentId, tick: Long): CreateClanOutcome =
        error("NoOpClanRegistry: createClan should not be called in this test")

    override fun findClan(clanId: ClanId): Clan? = null
    override fun clanOf(agentId: AgentId): ClanMembership? = null
    override fun roster(clanId: ClanId): List<ClanMember> = emptyList()
    override fun memberCount(clanId: ClanId): Int = 0

    override fun addMember(clanId: ClanId, agentId: AgentId, rank: ClanRank, tick: Long): AddMemberOutcome =
        error("NoOpClanRegistry: addMember should not be called in this test")

    override fun changeClanRank(clanId: ClanId, agentId: AgentId, newRank: ClanRank): Boolean =
        error("NoOpClanRegistry: changeClanRank should not be called in this test")

    override fun removeMember(clanId: ClanId, agentId: AgentId): Boolean =
        error("NoOpClanRegistry: removeMember should not be called in this test")

    override fun dissolve(clanId: ClanId): List<AgentId> =
        error("NoOpClanRegistry: dissolve should not be called in this test")
}
