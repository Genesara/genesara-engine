package dev.gvart.genesara.world.commands

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.CropId
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.RecipeId
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import java.util.UUID

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@type")
@JsonSubTypes(
    JsonSubTypes.Type(value = EconomyCommand.Harvest::class, name = "harvest"),
    JsonSubTypes.Type(value = EconomyCommand.CraftItem::class, name = "craft"),
    JsonSubTypes.Type(value = EconomyCommand.Extract::class, name = "extract"),
    JsonSubTypes.Type(value = EconomyCommand.TradeOffer::class, name = "tradeOffer"),
    JsonSubTypes.Type(value = EconomyCommand.TradeRespond::class, name = "tradeRespond"),
    JsonSubTypes.Type(value = EconomyCommand.PlantCrop::class, name = "plantCrop"),
    JsonSubTypes.Type(value = EconomyCommand.TendCrop::class, name = "tendCrop"),
    JsonSubTypes.Type(value = EconomyCommand.HarvestCrop::class, name = "harvestCrop"),
)
sealed interface EconomyCommand : WorldCommand {

    /**
     * Extract a single yield of [item] from the agent's current node. The item's
     * catalog entry (`Item.harvestSkill`) selects which skill is trained, if any.
     */
    data class Harvest(
        override val agent: AgentId,
        val item: ItemId,
        override val commandId: UUID = UUID.randomUUID(),
    ) : EconomyCommand

    /**
     * Craft a single output of [recipe] at the agent's current node. Spends
     * stamina and the recipe's input materials, rolls a per-instance Rarity
     * (equipment outputs only), and signs the resulting `ItemInstance.Equipment`
     * with the calling agent. Stackable outputs (potions, intermediates) skip
     * the rarity roll and the creator signature; the output is added to the
     * agent's inventory instead.
     *
     * [source] is required when the recipe declares `requiresSource`: a
     * UUID identifying an existing per-instance item the recipe operates on
     * (e.g. GATE_KEY_COPY references an existing GATE_KEY whose gate-binding
     * the new key inherits). Future upgrade-style recipes will use this
     * field to point at an existing `ItemInstance.Equipment` to refine.
     */
    data class CraftItem(
        override val agent: AgentId,
        val recipe: RecipeId,
        val source: UUID? = null,
        override val commandId: UUID = UUID.randomUUID(),
    ) : EconomyCommand

    /**
     * Pull a single yield of [item] from the agent's current node via a built
     * MINE. Mirrors [Harvest] but filters to items flagged `extractionOnly`
     * (COAL, ORE, GOLD) and requires an ACTIVE MINE building at the node.
     * MINING is the trained skill (via `Item.harvestSkill`).
     */
    data class Extract(
        override val agent: AgentId,
        val item: ItemId,
        override val commandId: UUID = UUID.randomUUID(),
    ) : EconomyCommand

    /**
     * Queue an offer to [recipient] for the atomic swap of [offered] (taken from the
     * offerer's inventory) against [requested] (taken from the recipient's inventory).
     * The reducer validates same-node co-location and the offerer's current stock, then
     * persists a PENDING trade row keyed by [tradeId]; no inventory mutates here.
     * The recipient must call [TradeRespond] to resolve.
     *
     * The recipient's stock is NOT validated at offer time — agents do not have
     * arbitrary read access to each other's inventories (information asymmetry per
     * design principle #7). The respond reducer re-validates both sides; a request
     * the recipient can't satisfy surfaces there as `ItemNotInInventory(recipient, ...)`.
     *
     * Per-instance items (equipment, keys, mount gear) travel in [offeredInstances] /
     * [requestedInstances] as raw UUIDs. Each side must be unbound at offer time AND
     * at respond time — not equipped in an agent slot, not equipped on a mount, not
     * stowed in a mount. The reducer rejects with `TradeInstanceNotFound`,
     * `TradeInstanceNotOwned`, or `TradeInstanceUnavailable` on violation. At
     * respond time both sides go through `AgentItemInstancesStore.reassignOwner`
     * inside the same transaction as the stackable swap.
     */
    data class TradeOffer(
        override val agent: AgentId,
        val recipient: AgentId,
        val offered: Map<ItemId, Int> = emptyMap(),
        val requested: Map<ItemId, Int> = emptyMap(),
        val offeredInstances: Set<UUID> = emptySet(),
        val requestedInstances: Set<UUID> = emptySet(),
        val tradeId: UUID = UUID.randomUUID(),
        override val commandId: UUID = UUID.randomUUID(),
    ) : EconomyCommand

    /**
     * Resolve a pending [TradeOffer] keyed by [tradeId]. The reducer rejects when the
     * caller is not the recipient, when the trade is no longer PENDING, or when either
     * party drifted off the offer's node. On [accept] = true both inventories swap
     * atomically; on false the trade flips to REJECTED with no inventory change.
     */
    data class TradeRespond(
        override val agent: AgentId,
        val tradeId: UUID,
        val accept: Boolean,
        override val commandId: UUID = UUID.randomUUID(),
    ) : EconomyCommand

    /**
     * Sow [crop] in an empty FARM_PLOT identified by [plotId]. Validates the
     * agent stands on the plot's node, owns the plot, the plot is empty, the
     * agent's FARMING level meets the crop's gate, the plot's terrain admits
     * the crop, and the agent carries the crop's seed item. Spends the crop's
     * plant-stamina and consumes one seed.
     */
    data class PlantCrop(
        override val agent: AgentId,
        val plotId: UUID,
        val crop: CropId,
        override val commandId: UUID = UUID.randomUUID(),
    ) : EconomyCommand

    /**
     * Refresh the neglect timer on a planted FARM_PLOT identified by [plotId].
     * Validates same-node + ownership + plot non-empty. Spends the crop's
     * tend-stamina; bumps `last_tended_at_tick` to the current tick.
     */
    data class TendCrop(
        override val agent: AgentId,
        val plotId: UUID,
        override val commandId: UUID = UUID.randomUUID(),
    ) : EconomyCommand

    /**
     * Reap a ripe FARM_PLOT identified by [plotId]. Validates same-node +
     * ownership + plot ripe. Spends the crop's harvest-stamina, deposits
     * `baseYield + floor(level * gainPerLevel) + uniform(0, maxLuckBonus)`
     * units of the crop's output item into the agent's inventory (subject
     * to carry-cap), and clears the plot back to empty.
     */
    data class HarvestCrop(
        override val agent: AgentId,
        val plotId: UUID,
        override val commandId: UUID = UUID.randomUUID(),
    ) : EconomyCommand
}
