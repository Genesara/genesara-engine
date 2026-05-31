package dev.gvart.genesara.world

import java.util.UUID

@JvmInline
value class FactionId(val value: UUID)

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
