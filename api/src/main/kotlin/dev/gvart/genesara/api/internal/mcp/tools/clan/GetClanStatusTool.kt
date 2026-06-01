package dev.gvart.genesara.api.internal.mcp.tools.clan

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityTracker
import dev.gvart.genesara.api.internal.mcp.presence.touchActivity
import dev.gvart.genesara.world.ClanRegistry
import dev.gvart.genesara.world.internal.balance.BalanceLookup
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.annotation.Tool
import org.springframework.stereotype.Component

@Component
internal class GetClanStatusTool(
    private val clans: ClanRegistry,
    private val balance: BalanceLookup,
    private val activity: AgentActivityTracker,
) {

    @Tool(
        name = "get_clan_status",
        description = "Sync-read your clan: id, name, faction, founding tick, member count and " +
            "cap, your own clan and faction rank, and the full roster (each member's ranks + join " +
            "tick). Returns `{clan: null}` when you are not in any clan. Member cap is 6 while the " +
            "clan owns no base; it grows as the clan captures territory.",
    )
    fun invoke(toolContext: ToolContext): GetClanStatusResponse {
        touchActivity(toolContext, activity, "get_clan_status")
        val agent = AgentContextHolder.current()
        val membership = clans.clanOf(agent) ?: return GetClanStatusResponse(clan = null)
        val clan = membership.clan
        val roster = clans.roster(clan.id)
        return GetClanStatusResponse(
            clan = ClanView(
                clanId = clan.id.value,
                name = clan.name,
                factionId = clan.factionId?.value,
                foundedAtTick = clan.foundedAtTick,
                memberCount = roster.size,
                memberCap = balance.baselineClanCapacity(),
                yourClanRank = membership.clanRank,
                yourFactionRank = membership.factionRank,
                members = roster.map {
                    ClanMemberView(
                        agentId = it.agentId.id,
                        clanRank = it.clanRank,
                        factionRank = it.factionRank,
                        joinedAtTick = it.joinedAtTick,
                    )
                },
            ),
        )
    }
}
