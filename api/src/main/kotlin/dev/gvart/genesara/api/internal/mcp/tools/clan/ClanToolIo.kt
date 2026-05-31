package dev.gvart.genesara.api.internal.mcp.tools.clan

import com.fasterxml.jackson.annotation.JsonInclude
import dev.gvart.genesara.player.FactionRank
import dev.gvart.genesara.world.ClanRank
import java.util.UUID

@JsonInclude(JsonInclude.Include.NON_NULL)
data class GetClanStatusResponse(
    val clan: ClanView? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ClanView(
    val clanId: UUID,
    val name: String,
    val factionId: UUID?,
    val foundedAtTick: Long,
    val memberCount: Int,
    /** Current member cap. v1 = the base-less baseline (6); grows with owned bases once #23 lands. */
    val memberCap: Int,
    val yourClanRank: ClanRank,
    val yourFactionRank: FactionRank?,
    val members: List<ClanMemberView>,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ClanMemberView(
    val agentId: UUID,
    val clanRank: ClanRank,
    val factionRank: FactionRank?,
    val joinedAtTick: Long,
)
