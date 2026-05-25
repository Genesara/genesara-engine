package dev.gvart.genesara.world

import dev.gvart.genesara.player.AgentId
import java.util.UUID

@JvmInline
value class PartyId(val value: UUID)

@JvmInline
value class PartyInviteId(val value: UUID)

data class PartyMember(
    val agentId: AgentId,
    val joinedAtTick: Long,
)

/**
 * Ephemeral group of co-operating agents. Members are ordered by [PartyMember.joinedAtTick]
 * ascending; the leader is always present in [members] and identified by [leaderId]. The
 * earliest-joined non-leader is the successor when the leader leaves via `leave_party` or
 * explicit unspawn — death and auto-unspawn keep the leader's seat.
 *
 * v1 lives only in Redis; the engine does not persist parties across restarts.
 */
data class Party(
    val partyId: PartyId,
    val leaderId: AgentId,
    val members: List<PartyMember>,
    val formedAtTick: Long,
) {
    val size: Int get() = members.size
    fun memberIds(): Set<AgentId> = members.map { it.agentId }.toSet()
    fun contains(agentId: AgentId): Boolean = members.any { it.agentId == agentId }
}

/**
 * Pending request from [inviterId] to [inviteeId]. Lives until the invitee responds,
 * the inviter leaves their party (sweep), or the Redis EXPIRE on the key fires —
 * whichever happens first. [expiresAtTick] is a client hint; Redis is the source of
 * truth via key TTL.
 */
data class PartyInvite(
    val inviteId: PartyInviteId,
    val inviterId: AgentId,
    val inviteeId: AgentId,
    val sentAtTick: Long,
    val expiresAtTick: Long,
)
