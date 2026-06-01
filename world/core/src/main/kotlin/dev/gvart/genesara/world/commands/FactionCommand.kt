package dev.gvart.genesara.world.commands

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.ClanId
import java.util.UUID

/**
 * Faction-layer verbs (#22). Per-zone sealed sub-hierarchy of [WorldCommand]; the authoritative
 * wire registry is in [WorldCommand]. Reduced by `:world:clan` `FactionReducer` at tick time.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@type")
@JsonSubTypes(
    JsonSubTypes.Type(value = FactionCommand.CreateFaction::class, name = "createFaction"),
    JsonSubTypes.Type(value = FactionCommand.InviteClanToFaction::class, name = "inviteClanToFaction"),
    JsonSubTypes.Type(value = FactionCommand.RespondFactionInvite::class, name = "respondFactionInvite"),
    JsonSubTypes.Type(value = FactionCommand.LeaveFaction::class, name = "leaveFaction"),
    JsonSubTypes.Type(value = FactionCommand.PromoteFactionMember::class, name = "promoteFactionMember"),
    JsonSubTypes.Type(value = FactionCommand.DemoteFactionMember::class, name = "demoteFactionMember"),
)
sealed interface FactionCommand : WorldCommand {

    /** Found a faction; the caller (their clan's Archon) becomes its Sovereign. */
    data class CreateFaction(
        override val agent: AgentId,
        val name: String,
        override val commandId: UUID = UUID.randomUUID(),
    ) : FactionCommand

    /** Sovereign/Pillar invites the clan [targetClanId] to join the caller's faction. */
    data class InviteClanToFaction(
        override val agent: AgentId,
        val targetClanId: ClanId,
        override val commandId: UUID = UUID.randomUUID(),
    ) : FactionCommand

    /** The target clan's Archon accepts or declines a pending faction invite. */
    data class RespondFactionInvite(
        override val agent: AgentId,
        val inviteId: UUID,
        val accept: Boolean,
        override val commandId: UUID = UUID.randomUUID(),
    ) : FactionCommand

    /** Clan Archon pulls their clan out of its faction; the faction dissolves if it was the last clan. */
    data class LeaveFaction(
        override val agent: AgentId,
        override val commandId: UUID = UUID.randomUUID(),
    ) : FactionCommand

    /** Sovereign raises [target] one faction rank (Pact→Speaker→Pillar; Sovereign is not assignable). */
    data class PromoteFactionMember(
        override val agent: AgentId,
        val target: AgentId,
        override val commandId: UUID = UUID.randomUUID(),
    ) : FactionCommand

    /** Sovereign lowers [target] one faction rank. */
    data class DemoteFactionMember(
        override val agent: AgentId,
        val target: AgentId,
        override val commandId: UUID = UUID.randomUUID(),
    ) : FactionCommand
}
