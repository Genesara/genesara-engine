package dev.gvart.genesara.world.commands

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.BuildingType
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.NodeId
import java.util.UUID

sealed interface WorldCommand {
    val agent: AgentId
    val commandId: UUID

    data class SpawnAgent(
        override val agent: AgentId,
        val at: NodeId,
        override val commandId: UUID = UUID.randomUUID(),
    ) : WorldCommand

    data class MoveAgent(
        override val agent: AgentId,
        val to: NodeId,
        override val commandId: UUID = UUID.randomUUID(),
    ) : WorldCommand

    data class UnspawnAgent(
        override val agent: AgentId,
        override val commandId: UUID = UUID.randomUUID(),
    ) : WorldCommand

    data class GatherResource(
        override val agent: AgentId,
        val item: ItemId,
        override val commandId: UUID = UUID.randomUUID(),
    ) : WorldCommand

    data class MineResource(
        override val agent: AgentId,
        val item: ItemId,
        override val commandId: UUID = UUID.randomUUID(),
    ) : WorldCommand

    data class ConsumeItem(
        override val agent: AgentId,
        val item: ItemId,
        override val commandId: UUID = UUID.randomUUID(),
    ) : WorldCommand

    data class Drink(
        override val agent: AgentId,
        override val commandId: UUID = UUID.randomUUID(),
    ) : WorldCommand

    /**
     * Bind the agent's current node as their respawn checkpoint. The reducer
     * validates the agent is positioned at the marker node — agents can't
     * pre-mark a remote location.
     */
    data class SetSafeNode(
        override val agent: AgentId,
        override val commandId: UUID = UUID.randomUUID(),
    ) : WorldCommand

    /**
     * Materialize a dead agent at their safe node. Validates the body is at
     * `hp == 0` and the agent is not currently in the world (the death sweep
     * removed them from `state.positions`).
     */
    data class Respawn(
        override val agent: AgentId,
        override val commandId: UUID = UUID.randomUUID(),
    ) : WorldCommand

    /**
     * Spend one work step on building [type] at the agent's current node.
     * The first call lays the foundation (creates an UNDER_CONSTRUCTION
     * instance, deducts step 1's materials + stamina); subsequent calls
     * advance the existing in-progress instance built by this agent of
     * this type on this node. The step that reaches the def's totalSteps
     * flips status to ACTIVE and triggers any per-type completion side-effect.
     */
    data class BuildStructure(
        override val agent: AgentId,
        val type: BuildingType,
        override val commandId: UUID = UUID.randomUUID(),
    ) : WorldCommand

    /** Move [quantity] of [item] from the agent's inventory into the chest building [chestId]. */
    data class DepositToChest(
        override val agent: AgentId,
        val chestId: UUID,
        val item: ItemId,
        val quantity: Int,
        override val commandId: UUID = UUID.randomUUID(),
    ) : WorldCommand

    /** Move [quantity] of [item] from the chest building [chestId] back into the agent's inventory. */
    data class WithdrawFromChest(
        override val agent: AgentId,
        val chestId: UUID,
        val item: ItemId,
        val quantity: Int,
        override val commandId: UUID = UUID.randomUUID(),
    ) : WorldCommand
}
