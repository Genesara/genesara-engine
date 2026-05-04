package dev.gvart.genesara.world.commands

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.BuildingType
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.RecipeId
import java.util.UUID

sealed interface WorldCommand {
    val agent: AgentId
    val commandId: UUID

    /**
     * Enter the world. The reducer resolves the destination via the canonical
     * fallback chain (resume last-known position → race-keyed starter node →
     * random spawnable node); the resolved node is reported on the resulting
     * [dev.gvart.genesara.world.events.WorldEvent.AgentSpawned].
     */
    data class SpawnAgent(
        override val agent: AgentId,
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

    /**
     * Extract a single yield of [item] from the agent's current node. The item's
     * catalog entry (`Item.gatheringSkill`) selects which skill is trained, if any.
     */
    data class Harvest(
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

    /**
     * Craft a single output of [recipe] at the agent's current node. Spends
     * stamina and the recipe's input materials, rolls a per-instance Rarity
     * (equipment outputs only), and signs the resulting [EquipmentInstance]
     * with the calling agent. Stackable outputs (potions, intermediates) skip
     * the rarity roll and the creator signature; the output is added to the
     * agent's inventory instead.
     */
    data class CraftItem(
        override val agent: AgentId,
        val recipe: RecipeId,
        override val commandId: UUID = UUID.randomUUID(),
    ) : WorldCommand
}
