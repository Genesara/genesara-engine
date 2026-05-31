package dev.gvart.genesara.world.events

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.ClanId
import dev.gvart.genesara.world.ClanRank
import java.util.UUID

/**
 * Clan-lifecycle events (#22). Per-zone sealed sub-hierarchy of [WorldEvent]. Each
 * variant carries an explicit [listeners] set — the dispatcher fans one envelope out
 * per listener to the agent's SSE stream. [causedBy] is the originating command id.
 */
sealed interface ClanEvent : WorldEvent {

    /** An agent joined a clan (founding Archon on create, or an accepted invitee). */
    data class ClanJoined(
        val clanId: ClanId,
        val agent: AgentId,
        val clanRank: ClanRank,
        val listeners: Set<AgentId>,
        override val tick: Long,
        val causedBy: UUID,
    ) : ClanEvent

    /** An agent left a clan (voluntarily or kicked). */
    data class ClanLeft(
        val clanId: ClanId,
        val agent: AgentId,
        val reason: Reason,
        val listeners: Set<AgentId>,
        override val tick: Long,
        val causedBy: UUID,
    ) : ClanEvent {
        enum class Reason { LEFT, KICKED }
    }

    /** A clan was dissolved — by its Archon, or by the last member leaving. */
    data class ClanDissolved(
        val clanId: ClanId,
        val listeners: Set<AgentId>,
        override val tick: Long,
        val causedBy: UUID,
    ) : ClanEvent

    /** A member's clan rank changed (promotion, demotion, or leadership transfer). */
    data class RankChanged(
        val clanId: ClanId,
        val agent: AgentId,
        val previousRank: ClanRank,
        val newRank: ClanRank,
        val listeners: Set<AgentId>,
        override val tick: Long,
        val causedBy: UUID,
    ) : ClanEvent
}
