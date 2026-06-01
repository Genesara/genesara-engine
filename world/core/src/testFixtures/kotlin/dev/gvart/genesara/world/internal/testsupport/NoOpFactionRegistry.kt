package dev.gvart.genesara.world.internal.testsupport

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.FactionRank
import dev.gvart.genesara.world.ClanId
import dev.gvart.genesara.world.CreateFactionOutcome
import dev.gvart.genesara.world.Faction
import dev.gvart.genesara.world.FactionId
import dev.gvart.genesara.world.FactionRegistry
import dev.gvart.genesara.world.LeaveFactionResult

/** Empty [FactionRegistry] for tests that don't exercise faction behavior. */
object NoOpFactionRegistry : FactionRegistry {
    override fun createFaction(name: String, founderClanId: ClanId, founderArchon: AgentId, tick: Long): CreateFactionOutcome =
        error("NoOpFactionRegistry: createFaction should not be called in this test")

    override fun findFaction(factionId: FactionId): Faction? = null
    override fun factionOf(clanId: ClanId): Faction? = null

    override fun joinFaction(factionId: FactionId, clanId: ClanId): Map<AgentId, FactionRank> =
        error("NoOpFactionRegistry: joinFaction should not be called in this test")

    override fun leaveFaction(clanId: ClanId): LeaveFactionResult =
        error("NoOpFactionRegistry: leaveFaction should not be called in this test")

    override fun setFactionRank(agentId: AgentId, rank: FactionRank): Unit =
        error("NoOpFactionRegistry: setFactionRank should not be called in this test")

    override fun memberClanIds(factionId: FactionId): List<ClanId> = emptyList()
    override fun agentsInFaction(factionId: FactionId): List<AgentId> = emptyList()

    override fun deleteFaction(factionId: FactionId): Unit =
        error("NoOpFactionRegistry: deleteFaction should not be called in this test")
}
