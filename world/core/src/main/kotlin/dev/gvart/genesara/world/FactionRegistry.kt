package dev.gvart.genesara.world

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.FactionRank

/**
 * Persistent store for factions + clan↔faction membership (mechanics-reference §18). Operates
 * on the same `clans` / `clan_members` tables as [ClanRegistry] (`clans.faction_id` +
 * `clan_members.faction_rank`) plus the `factions` table. The per-agent `faction_rank` it writes
 * is authoritative; callers mirror it onto `:player.agents.faction_rank` (the skill-slot bridge)
 * via [AgentRegistry.setFactionRank][dev.gvart.genesara.player.AgentRegistry.setFactionRank].
 * Production impl is `:world:clan` `JooqFactionRegistry`.
 */
interface FactionRegistry {

    /**
     * Create [name], link [founderClanId] to it, and rank the founding clan's members
     * ([founderArchon] → [FactionRank.SOVEREIGN], everyone else → [FactionRank.PACT]). Returns the
     * new faction plus the per-agent rank map so the caller can mirror to `:player` and emit events.
     */
    fun createFaction(name: String, founderClanId: ClanId, founderArchon: AgentId, tick: Long): CreateFactionOutcome

    fun findFaction(factionId: FactionId): Faction?

    /** The faction the clan belongs to, or null when the clan is in no faction. */
    fun factionOf(clanId: ClanId): Faction?

    /** Link [clanId] to [factionId], assigning every member [FactionRank.PACT]. Returns the per-agent ranks. */
    fun joinFaction(factionId: FactionId, clanId: ClanId): Map<AgentId, FactionRank>

    /**
     * Unlink [clanId] from its faction and clear every member's faction rank. Returns the cleared
     * member ids and whether the faction now has no member clans (the caller then dissolves it).
     */
    fun leaveFaction(clanId: ClanId): LeaveFactionResult

    /** Set [agentId]'s faction rank (faction promote / demote). */
    fun setFactionRank(agentId: AgentId, rank: FactionRank)

    /** Clan ids currently linked to [factionId]. */
    fun memberClanIds(factionId: FactionId): List<ClanId>

    /** Every agent currently in [factionId] across its member clans — used for event listeners. */
    fun agentsInFaction(factionId: FactionId): List<AgentId>

    /** Delete the faction row (members must already be unlinked via [leaveFaction]). */
    fun deleteFaction(factionId: FactionId)
}

/** Result of [FactionRegistry.createFaction]. */
sealed interface CreateFactionOutcome {
    data class Created(val faction: Faction, val memberRanks: Map<AgentId, FactionRank>) : CreateFactionOutcome
    data object NameTaken : CreateFactionOutcome
}

/** Result of [FactionRegistry.leaveFaction]. */
data class LeaveFactionResult(
    val factionId: FactionId,
    val clearedAgents: List<AgentId>,
    val factionEmptied: Boolean,
)
