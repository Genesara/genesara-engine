package dev.gvart.genesara.world.commands

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import dev.gvart.genesara.player.AgentId
import java.util.UUID

/**
 * Clan-lifecycle verbs (#22). Per-zone sealed sub-hierarchy of [WorldCommand]; the
 * `@JsonSubTypes` mirror here is forward-prep, with the authoritative wire registry
 * in [WorldCommand]. Reduced by `:world:clan` `ClanReducer` at tick time.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@type")
@JsonSubTypes(
    JsonSubTypes.Type(value = ClanCommand.CreateClan::class, name = "createClan"),
    JsonSubTypes.Type(value = ClanCommand.LeaveClan::class, name = "leaveClan"),
    JsonSubTypes.Type(value = ClanCommand.DissolveClan::class, name = "dissolveClan"),
    JsonSubTypes.Type(value = ClanCommand.TransferClanLeadership::class, name = "transferClanLeadership"),
)
sealed interface ClanCommand : WorldCommand {

    /** Found a new clan named [name]; the caller becomes its [dev.gvart.genesara.world.ClanRank.ARCHON]. */
    data class CreateClan(
        override val agent: AgentId,
        val name: String,
        override val commandId: UUID = UUID.randomUUID(),
    ) : ClanCommand

    /**
     * Leave the caller's clan. A sole [dev.gvart.genesara.world.ClanRank.ARCHON] with other
     * members must hand off first via [TransferClanLeadership]; an Archon who is the last
     * remaining member dissolves the clan by leaving.
     */
    data class LeaveClan(
        override val agent: AgentId,
        override val commandId: UUID = UUID.randomUUID(),
    ) : ClanCommand

    /** Archon-only: dissolve the caller's clan, releasing every member. */
    data class DissolveClan(
        override val agent: AgentId,
        override val commandId: UUID = UUID.randomUUID(),
    ) : ClanCommand

    /**
     * Archon-only: promote [target] (a member of the caller's clan) to
     * [dev.gvart.genesara.world.ClanRank.ARCHON]; the caller steps down to
     * [dev.gvart.genesara.world.ClanRank.VANGUARD].
     */
    data class TransferClanLeadership(
        override val agent: AgentId,
        val target: AgentId,
        override val commandId: UUID = UUID.randomUUID(),
    ) : ClanCommand
}
