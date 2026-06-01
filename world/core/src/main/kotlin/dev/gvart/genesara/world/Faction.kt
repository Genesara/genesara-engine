package dev.gvart.genesara.world

import java.util.UUID

@JvmInline
value class FactionId(val value: UUID)

@JvmInline
value class FactionInviteId(val value: UUID)

/**
 * Pending invitation for the clan [targetClanId] to join [factionId], sent by a faction
 * Sovereign/Pillar. The target clan's Archon accepts. Lives until responded or the Redis
 * TTL fires; [expiresAtTick] is a client hint, Redis key TTL is the source of truth.
 */
data class FactionInvite(
    val inviteId: FactionInviteId,
    val factionId: FactionId,
    val targetClanId: ClanId,
    val sentAtTick: Long,
    val expiresAtTick: Long,
)

/**
 * Clan-of-clans alliance (mechanics-reference §18). Clans join via an invite
 * handshake (Sovereign/Pillar invites; the joining clan's Archon accepts); an
 * agent's faction membership is derived through their clan. Faction-rank slots
 * bonus wired in Slice 4.
 */
data class Faction(
    val id: FactionId,
    val name: String,
    val foundedAtTick: Long,
)
