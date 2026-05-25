package dev.gvart.genesara.world.commands

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import dev.gvart.genesara.player.AgentId
import java.util.UUID

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@type")
@JsonSubTypes(
    JsonSubTypes.Type(value = SocialCommand.PartyInvite::class, name = "partyInvite"),
    JsonSubTypes.Type(value = SocialCommand.PartyRespond::class, name = "partyRespond"),
    JsonSubTypes.Type(value = SocialCommand.LeaveParty::class, name = "leaveParty"),
    JsonSubTypes.Type(value = SocialCommand.KickPartyMember::class, name = "kickPartyMember"),
)
sealed interface SocialCommand : WorldCommand {

    /**
     * Invite one or more agents to the calling agent's party. The agent must be
     * spawned, and each [invitees] target must be spawned AND inside the agent's
     * current vision (vision-gated invite per design Q5a).
     *
     * Auto-create-on-first-accept: no party row exists at invite-send time. The
     * caller is treated as the leader-to-be when they are solo; otherwise they
     * must already lead the party (only leaders may invite).
     *
     * Cap math: `current_members + pending_invites_already_sent + invitees.size`
     * must not exceed [dev.gvart.genesara.world.internal.balance.BalanceLookup.partyMaxSize].
     * Pending invites for `invitees` already in flight from the same inviter are
     * not double-counted — re-issuing the same invite is a no-op on the cap.
     */
    data class PartyInvite(
        override val agent: AgentId,
        val invitees: List<AgentId>,
        override val commandId: UUID = UUID.randomUUID(),
    ) : SocialCommand

    /**
     * Respond to a pending [inviteId] addressed to the calling agent. On [accept]
     * = true the resolver looks up the inviter's current party (creating one if
     * the inviter is solo) and appends the responder. Stale invites resolve as
     * [dev.gvart.genesara.world.WorldRejection.PartyInviteNotFound].
     */
    data class PartyRespond(
        override val agent: AgentId,
        val inviteId: UUID,
        val accept: Boolean,
        override val commandId: UUID = UUID.randomUUID(),
    ) : SocialCommand

    /**
     * Exit the calling agent's current party. When the caller is the leader of
     * a party with two or more remaining members, leadership transfers to the
     * earliest-joined remaining member (tiebreak by agent id). When the post-
     * removal size is one, the party auto-dissolves and the last member is
     * cleared as well.
     */
    data class LeaveParty(
        override val agent: AgentId,
        override val commandId: UUID = UUID.randomUUID(),
    ) : SocialCommand

    /**
     * Leader-only verb: remove [target] from the leader's party. Reads as the
     * leaver's [LeaveParty] from the target's perspective; emits a `PartyLeft`
     * with `reason = KICKED`. The leader may not kick themselves — use
     * [LeaveParty] for self-removal.
     */
    data class KickPartyMember(
        override val agent: AgentId,
        val target: AgentId,
        override val commandId: UUID = UUID.randomUUID(),
    ) : SocialCommand
}
