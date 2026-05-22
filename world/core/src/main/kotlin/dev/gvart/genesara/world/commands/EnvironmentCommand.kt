package dev.gvart.genesara.world.commands

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.world.BuildingType
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.MountId
import dev.gvart.genesara.world.NpcId
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import java.util.UUID

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@type")
@JsonSubTypes(
    JsonSubTypes.Type(value = EnvironmentCommand.BuildStructure::class, name = "build"),
    JsonSubTypes.Type(value = EnvironmentCommand.DepositToChest::class, name = "depositToChest"),
    JsonSubTypes.Type(value = EnvironmentCommand.WithdrawFromChest::class, name = "withdrawFromChest"),
    JsonSubTypes.Type(value = EnvironmentCommand.ToggleGate::class, name = "toggleGate"),
    JsonSubTypes.Type(value = EnvironmentCommand.Tame::class, name = "tame"),
    JsonSubTypes.Type(value = EnvironmentCommand.MountTransport::class, name = "mountTransport"),
    JsonSubTypes.Type(value = EnvironmentCommand.DismountTransport::class, name = "dismountTransport"),
    JsonSubTypes.Type(value = EnvironmentCommand.MaintainTransport::class, name = "maintainTransport"),
    JsonSubTypes.Type(value = EnvironmentCommand.ReleaseTransport::class, name = "releaseTransport"),
    JsonSubTypes.Type(value = EnvironmentCommand.ClaimTransport::class, name = "claimTransport"),
)
sealed interface EnvironmentCommand : WorldCommand {

    /**
     * Spend one work step on building [type] at the agent's current node, advancing
     * the [skill]-bar. Single-bar buildings auto-default [skill] to the only bar when
     * `null`; multi-bar buildings require an explicit skill that matches one of their
     * declared bars. The step that reaches the def's aggregate totalSteps flips
     * status to ACTIVE and triggers any per-type completion side-effect.
     */
    data class BuildStructure(
        override val agent: AgentId,
        val type: BuildingType,
        val skill: SkillId? = null,
        override val commandId: UUID = UUID.randomUUID(),
    ) : EnvironmentCommand

    /** Move [quantity] of [item] from the agent's inventory into the chest building [chestId]. */
    data class DepositToChest(
        override val agent: AgentId,
        val chestId: UUID,
        val item: ItemId,
        val quantity: Int,
        override val commandId: UUID = UUID.randomUUID(),
    ) : EnvironmentCommand

    /** Move [quantity] of [item] from the chest building [chestId] back into the agent's inventory. */
    data class WithdrawFromChest(
        override val agent: AgentId,
        val chestId: UUID,
        val item: ItemId,
        val quantity: Int,
        override val commandId: UUID = UUID.randomUUID(),
    ) : EnvironmentCommand

    /**
     * Flip the OPEN/CLOSED state of GATE [gateId]. Requires the agent to be
     * standing on the gate's node AND to hold a matching key. Passage through
     * a GATE is gated by its state; passage through a WOODEN_WALL is always
     * blocked.
     */
    data class ToggleGate(
        override val agent: AgentId,
        val gateId: UUID,
        override val commandId: UUID = UUID.randomUUID(),
    ) : EnvironmentCommand

    /**
     * Attempt to tame [target] (a Tier-A NPC same-node with the agent, listed
     * in the mounts catalog's `tamed-from`). Costs stamina regardless of
     * outcome; success deletes the NPC and inserts a Mount owned by [agent].
     */
    data class Tame(
        override val agent: AgentId,
        val target: NpcId,
        override val commandId: UUID = UUID.randomUUID(),
    ) : EnvironmentCommand

    /** Mount [mount] (same-node, mount alive, mount idle, agent not already on something else). */
    data class MountTransport(
        override val agent: AgentId,
        val mount: MountId,
        override val commandId: UUID = UUID.randomUUID(),
    ) : EnvironmentCommand

    /** Dismount the mount the agent is currently on. */
    data class DismountTransport(
        override val agent: AgentId,
        override val commandId: UUID = UUID.randomUUID(),
    ) : EnvironmentCommand

    /**
     * Apply [quantity] of maintenance [resource] to [mount]. Matches
     * `Item.maintenance.type` against the mount's accepted maintenance type
     * and restores `value × quantity` to the mount's maintenance gauge
     * (hunger for ANIMAL). Same-node required; not owner-gated.
     */
    data class MaintainTransport(
        override val agent: AgentId,
        val mount: MountId,
        val resource: ItemId,
        val quantity: Int,
        override val commandId: UUID = UUID.randomUUID(),
    ) : EnvironmentCommand

    /** Relinquish ownership of [mount] (owner-only; clears `owner_agent_id`). */
    data class ReleaseTransport(
        override val agent: AgentId,
        val mount: MountId,
        override val commandId: UUID = UUID.randomUUID(),
    ) : EnvironmentCommand

    /** Claim ownership of a currently-ownerless [mount] (agent must be same-node and under cap). */
    data class ClaimTransport(
        override val agent: AgentId,
        val mount: MountId,
        override val commandId: UUID = UUID.randomUUID(),
    ) : EnvironmentCommand
}
