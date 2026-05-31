package dev.gvart.genesara.world

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.FactionRank
import java.util.UUID

@JvmInline
value class ClanId(val value: UUID)

/**
 * Persistent clan — the agent-grouping above parties (mechanics-reference §18).
 * Created as a pure social act (#22): a founder calls `create_clan` and becomes
 * the [ClanRank.ARCHON]. [factionId] is null until the clan joins a faction. Node
 * ownership (clan-level) lands with #23 territory.
 */
data class Clan(
    val id: ClanId,
    val name: String,
    val factionId: FactionId?,
    val foundedAtTick: Long,
)

/**
 * A single clan membership row. [factionRank] is the agent's rank within the clan's
 * faction (null when the clan is in no faction); authoritative here and mirrored onto
 * `:player.agents.faction_rank` for the skill-slot formula.
 */
data class ClanMember(
    val clanId: ClanId,
    val agentId: AgentId,
    val clanRank: ClanRank,
    val factionRank: FactionRank?,
    val joinedAtTick: Long,
)

/** An agent's membership lookup result — the `clanOf(agent)` projection. */
data class ClanMembership(
    val clan: Clan,
    val clanRank: ClanRank,
    val factionRank: FactionRank?,
)
