package dev.gvart.genesara.world.commands

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.world.BuildingType
import dev.gvart.genesara.world.ItemId
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import java.util.UUID

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@type")
@JsonSubTypes(
    JsonSubTypes.Type(value = EnvironmentCommand.BuildStructure::class, name = "build"),
    JsonSubTypes.Type(value = EnvironmentCommand.DepositToChest::class, name = "depositToChest"),
    JsonSubTypes.Type(value = EnvironmentCommand.WithdrawFromChest::class, name = "withdrawFromChest"),
    JsonSubTypes.Type(value = EnvironmentCommand.ToggleGate::class, name = "toggleGate"),
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
}
