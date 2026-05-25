package dev.gvart.genesara.world.events

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.OutlawState
import dev.gvart.genesara.world.PartyId
import dev.gvart.genesara.world.PartyInviteId
import dev.gvart.genesara.world.PartyMember
import java.util.UUID

sealed interface SocialEvent : WorldEvent {

    /**
     * Emitted per invitee on a successful `party_invite`. Routed to the invitee
     * only — the inviter already knows their own call succeeded via the queue ack.
     * [expiresAtTick] is a client hint so the invitee can plan around the TTL
     * without having to track wall-clock time; Redis EXPIRE on the invite key is
     * authoritative.
     */
    data class PartyInviteReceived(
        val inviteId: PartyInviteId,
        val inviter: AgentId,
        val invitee: AgentId,
        val sentAtTick: Long,
        val expiresAtTick: Long,
        val listeners: Set<AgentId>,
        override val tick: Long,
        val causedBy: UUID,
    ) : SocialEvent

    /** Invitee declined the invite. Routed to the inviter only. */
    data class PartyInviteDeclined(
        val inviteId: PartyInviteId,
        val inviter: AgentId,
        val invitee: AgentId,
        val listeners: Set<AgentId>,
        override val tick: Long,
        val causedBy: UUID,
    ) : SocialEvent

    /**
     * Inviter's outstanding invites were swept because they left their party or
     * the last member of a party dissolved it. Routed to both parties so they
     * each see the door closed.
     */
    data class PartyInviteCancelled(
        val inviteId: PartyInviteId,
        val inviter: AgentId,
        val invitee: AgentId,
        val listeners: Set<AgentId>,
        override val tick: Long,
        val causedBy: UUID,
    ) : SocialEvent

    /**
     * Emitted on a successful accept — either when the inviter's first acceptor
     * brings the party into being or when an additional invitee joins an
     * existing party. [members] is the post-join roster; [leader] is the
     * current leader. Routed to every member (the new joiner included) so each
     * receives a single authoritative snapshot without follow-up reads.
     */
    data class PartyJoined(
        val partyId: PartyId,
        val joiner: AgentId,
        val leader: AgentId,
        val members: List<PartyMember>,
        val listeners: Set<AgentId>,
        override val tick: Long,
        val causedBy: UUID,
    ) : SocialEvent

    /**
     * Member departure under any mode. Routed to the leaver AND every remaining
     * member; the remaining-member set carries the post-removal roster.
     *
     * On a leader-leaves-with-2+-remaining path the dispatcher emits this event
     * BEFORE [PartyLeadershipTransferred] so order is `leave → transfer`.
     *
     * On the auto-dissolve path (post-removal size of 1) the dispatcher emits
     * this AND the trailing [PartyDissolved] so consumers see both signals.
     */
    data class PartyLeft(
        val partyId: PartyId,
        val leaver: AgentId,
        val leader: AgentId,
        val members: List<PartyMember>,
        val reason: Reason,
        val listeners: Set<AgentId>,
        override val tick: Long,
        val causedBy: UUID,
    ) : SocialEvent {
        enum class Reason { LEFT, KICKED, UNSPAWNED }
    }

    /**
     * Leadership passed from [previousLeader] (who just left) to [newLeader]
     * (earliest-joined remaining member). Routed to the post-transfer member
     * set including the new leader; the old leader is not a listener (they
     * already saw [PartyLeft]).
     */
    data class PartyLeadershipTransferred(
        val partyId: PartyId,
        val previousLeader: AgentId,
        val newLeader: AgentId,
        val members: List<PartyMember>,
        val listeners: Set<AgentId>,
        override val tick: Long,
        val causedBy: UUID,
    ) : SocialEvent

    /**
     * Emitted on auto-disband (post-removal size = 1) or when an explicit leave
     * brings the party to zero. [finalMembers] captures the roster at the
     * moment of dissolution so consumers see who was present at the end.
     * Listeners include every member who was in the party at dissolution time.
     */
    data class PartyDissolved(
        val partyId: PartyId,
        val finalMembers: List<PartyMember>,
        val listeners: Set<AgentId>,
        override val tick: Long,
        val causedBy: UUID,
    ) : SocialEvent

    /**
     * Mechanics-reference §11 outlaw state transition. Emitted when the
     * agent's misconduct write or the periodic decay sweep crosses a bucket
     * boundary. Routed to the affected agent's own stream only — outlaw
     * status is private until observed via inspect.
     *
     * `causedBy` is the attack `commandId` when accrual fires inside the
     * AttackReducer, and `null` when the decay sweep is the trigger (no
     * single command caused it).
     */
    data class OutlawStateChanged(
        val agent: AgentId,
        val previousState: OutlawState,
        val newState: OutlawState,
        val score: Int,
        val listeners: Set<AgentId>,
        override val tick: Long,
        val causedBy: UUID?,
    ) : SocialEvent
}
