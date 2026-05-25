package dev.gvart.genesara.world.internal.party

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.Party
import dev.gvart.genesara.world.PartyInviteStore
import dev.gvart.genesara.world.PartyMember
import dev.gvart.genesara.world.PartyStore
import dev.gvart.genesara.world.events.SocialEvent
import dev.gvart.genesara.world.events.WorldEvent
import java.util.UUID

/**
 * Shared leave-or-kick effect logic, callable both from `leave_party` /
 * `kick_member` reducers in `:world:social` and from `reduceUnspawn` in
 * `:world:core` (explicit unspawn is a party-leave per design Q4c).
 *
 * Performs the Redis mutations on [partyStore] and [partyInviteStore] in
 * order and returns the ordered event list:
 *  - `PartyLeft` first (always, includes the leaver in the listener set);
 *  - `PartyLeadershipTransferred` when the leaver was the leader and ≥2
 *    members remain;
 *  - `PartyInviteCancelled` per swept invite when the leaver was the leader
 *    (sweep covers the leader's outgoing invites) OR when the party itself
 *    dissolves (sweep covers the dissolving party's leader's invites);
 *  - `PartyDissolved` last when the post-removal size drops to 1.
 *
 * Mutation order (validate → write → event emission within the caller's
 * reducer body) keeps the partial-failure window narrow given the
 * single-threaded `WorldTickHandler` execution model.
 */
fun applyPartyLeave(
    party: Party,
    leaver: AgentId,
    reason: SocialEvent.PartyLeft.Reason,
    partyStore: PartyStore,
    partyInviteStore: PartyInviteStore,
    causedBy: UUID,
    tick: Long,
): List<WorldEvent> {
    val wasLeader = party.leaderId == leaver
    val remaining = party.members.filter { it.agentId != leaver }
    val events = mutableListOf<WorldEvent>()

    if (remaining.size <= 1) {
        val finalListeners = (remaining.map { it.agentId } + leaver).toSet()
        events += SocialEvent.PartyLeft(
            partyId = party.partyId,
            leaver = leaver,
            leader = party.leaderId,
            members = remaining,
            reason = reason,
            listeners = finalListeners,
            tick = tick,
            causedBy = causedBy,
        )
        val cancelled = partyInviteStore.deleteAllByInviter(party.leaderId)
        for (invite in cancelled) {
            events += SocialEvent.PartyInviteCancelled(
                inviteId = invite.inviteId,
                inviter = invite.inviterId,
                invitee = invite.inviteeId,
                listeners = setOf(invite.inviteeId, invite.inviterId),
                tick = tick,
                causedBy = causedBy,
            )
        }
        partyStore.delete(party.partyId)
        events += SocialEvent.PartyDissolved(
            partyId = party.partyId,
            finalMembers = remaining,
            listeners = finalListeners,
            tick = tick,
            causedBy = causedBy,
        )
        return events
    }

    val nextLeader = if (wasLeader) successor(remaining) else party.leaderId
    val updated = ensureMutated(partyStore.removeMember(party.partyId, leaver))
    val afterRemoval = if (wasLeader && nextLeader != updated.leaderId) {
        ensureMutated(partyStore.replaceLeader(party.partyId, nextLeader))
    } else {
        updated
    }
    val listeners = afterRemoval.memberIds() + leaver

    events += SocialEvent.PartyLeft(
        partyId = party.partyId,
        leaver = leaver,
        leader = afterRemoval.leaderId,
        members = afterRemoval.members,
        reason = reason,
        listeners = listeners,
        tick = tick,
        causedBy = causedBy,
    )

    if (wasLeader) {
        events += SocialEvent.PartyLeadershipTransferred(
            partyId = party.partyId,
            previousLeader = leaver,
            newLeader = afterRemoval.leaderId,
            members = afterRemoval.members,
            listeners = afterRemoval.memberIds(),
            tick = tick,
            causedBy = causedBy,
        )
        val cancelled = partyInviteStore.deleteAllByInviter(leaver)
        for (invite in cancelled) {
            events += SocialEvent.PartyInviteCancelled(
                inviteId = invite.inviteId,
                inviter = invite.inviterId,
                invitee = invite.inviteeId,
                listeners = setOf(invite.inviteeId, invite.inviterId),
                tick = tick,
                causedBy = causedBy,
            )
        }
    }

    return events
}

private fun successor(remaining: List<PartyMember>): AgentId =
    remaining.minWith(compareBy({ it.joinedAtTick }, { it.agentId.id })).agentId

private fun ensureMutated(party: Party?): Party =
    party ?: error("party-store mutation returned null on a party we just read — invariant broken")
