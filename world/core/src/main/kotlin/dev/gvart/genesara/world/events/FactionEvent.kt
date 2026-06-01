package dev.gvart.genesara.world.events

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.FactionRank
import dev.gvart.genesara.world.ClanId
import dev.gvart.genesara.world.FactionId
import dev.gvart.genesara.world.FactionInviteId
import java.util.UUID

/**
 * Faction-layer events (#22). Per-zone sealed sub-hierarchy of [WorldEvent]; each variant fans
 * out to its explicit [listeners] set on the per-agent SSE stream.
 */
sealed interface FactionEvent : WorldEvent {

    /** A clan founded a faction (its members are the initial faction membership). */
    data class FactionFormed(
        val factionId: FactionId,
        val founderClanId: ClanId,
        val listeners: Set<AgentId>,
        override val tick: Long,
        val causedBy: UUID,
    ) : FactionEvent

    /** A clan was invited to join a faction (delivered to the target clan's Archon). */
    data class FactionInviteReceived(
        val factionId: FactionId,
        val inviteId: FactionInviteId,
        val targetClanId: ClanId,
        val listeners: Set<AgentId>,
        override val tick: Long,
        val causedBy: UUID,
    ) : FactionEvent

    /** A clan joined a faction. */
    data class FactionJoined(
        val factionId: FactionId,
        val clanId: ClanId,
        val listeners: Set<AgentId>,
        override val tick: Long,
        val causedBy: UUID,
    ) : FactionEvent

    /** A clan left a faction. */
    data class FactionLeft(
        val factionId: FactionId,
        val clanId: ClanId,
        val listeners: Set<AgentId>,
        override val tick: Long,
        val causedBy: UUID,
    ) : FactionEvent

    /** A faction was dissolved (its last clan left). */
    data class FactionDissolved(
        val factionId: FactionId,
        val listeners: Set<AgentId>,
        override val tick: Long,
        val causedBy: UUID,
    ) : FactionEvent

    /** An agent's faction rank changed (promotion / demotion). */
    data class FactionRankChanged(
        val factionId: FactionId,
        val agent: AgentId,
        val previousRank: FactionRank,
        val newRank: FactionRank,
        val listeners: Set<AgentId>,
        override val tick: Long,
        val causedBy: UUID,
    ) : FactionEvent
}
