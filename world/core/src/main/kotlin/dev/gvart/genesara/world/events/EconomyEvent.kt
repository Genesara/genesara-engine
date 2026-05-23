package dev.gvart.genesara.world.events

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.CropId
import dev.gvart.genesara.world.DroppedItemView
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.Rarity
import dev.gvart.genesara.world.RecipeId
import java.util.UUID

sealed interface EconomyEvent : WorldEvent {

    /**
     * Emitted by the harvest reducer when an agent successfully extracts [quantity] of
     * [item] from [at].
     */
    data class ResourceHarvested(
        val agent: AgentId,
        val at: NodeId,
        val item: ItemId,
        val quantity: Int,
        override val tick: Long,
        val causedBy: UUID,
    ) : EconomyEvent

    /**
     * Agent finished a [recipe]: inputs consumed, output produced. Equipment
     * outputs persist as a fresh `agent_item_instances` row with
     * [instanceId] populated and [rarity] rolled from the agent's skill +
     * Luck at craft time. Stackable outputs (potions, intermediates) carry
     * `instanceId = null` and `rarity = null` — the resulting quantity lands
     * in the agent's stackable inventory and is not signed by the creator.
     */
    data class ItemCrafted(
        val agent: AgentId,
        val at: NodeId,
        val recipe: RecipeId,
        val output: ItemId,
        val quantity: Int,
        val instanceId: UUID?,
        val rarity: Rarity?,
        override val tick: Long,
        val causedBy: UUID,
    ) : EconomyEvent

    /**
     * Fired alongside [dev.gvart.genesara.world.events.BodyEvent.AgentDied] when the
     * kill-streak drop hook produced a drop. Lets agents who weren't the dying one
     * notice that a new ground item appeared at their tile — they can call `pickup`
     * with [drop.dropId] if they're standing there.
     *
     * `causedBy` is null for starvation deaths (the sweep is not a queued
     * command). Combat deaths populate it with the killing attack's
     * commandId, mirroring [dev.gvart.genesara.world.events.BodyEvent.AgentDied.causedBy].
     */
    data class ItemDroppedOnGround(
        val at: NodeId,
        /**
         * The agent whose action freed this item — the dying agent on death,
         * the previous owner on mount-cargo drops. Null when the drop has no
         * agent attribution (stackable cargo emptied off a starved mount).
         * Per-agent event routing skips null; the drop is still visible via
         * `look_around`.
         */
        val byAgent: AgentId?,
        val drop: DroppedItemView,
        override val tick: Long,
        val causedBy: UUID?,
    ) : EconomyEvent

    /**
     * Plant reducer fired: an empty FARM_PLOT now carries a [crop] planted at
     * [plantedAtTick]. [ripeAtTick] is the absolute tick at which the harvest
     * gate opens — pre-computed so agents don't have to re-derive it from the
     * crop catalog client-side.
     */
    data class CropPlanted(
        val agent: AgentId,
        val at: NodeId,
        val plotId: UUID,
        val crop: CropId,
        val plantedAtTick: Long,
        val ripeAtTick: Long,
        override val tick: Long,
        val causedBy: UUID,
    ) : EconomyEvent

    /** Tend reducer fired: a planted plot's neglect timer was refreshed. */
    data class CropTended(
        val agent: AgentId,
        val at: NodeId,
        val plotId: UUID,
        val crop: CropId,
        override val tick: Long,
        val causedBy: UUID,
    ) : EconomyEvent

    /**
     * Harvest reducer fired against a ripe plot: the plot is back to empty
     * and [quantity] units of [outputItem] (after FARMING-level + Luck
     * scaling) landed in the agent's inventory.
     */
    data class CropHarvested(
        val agent: AgentId,
        val at: NodeId,
        val plotId: UUID,
        val crop: CropId,
        val outputItem: ItemId,
        val quantity: Int,
        override val tick: Long,
        val causedBy: UUID,
    ) : EconomyEvent

    /**
     * Per-tick decay sweep cleared a planted plot whose `last_tended_at_tick`
     * fell behind the crop's neglect window. `causedBy` is null because the
     * sweep runs without a queued command — same shape as the starvation
     * branch of [dev.gvart.genesara.world.events.BodyEvent.AgentDied].
     */
    data class CropDied(
        val agent: AgentId,
        val at: NodeId,
        val plotId: UUID,
        val crop: CropId,
        val neglectedSinceTick: Long,
        override val tick: Long,
    ) : EconomyEvent

    /**
     * Emitted on a successful `extract`. Mirrors [ResourceHarvested] shape;
     * separate event so consumers can distinguish MINE-gated pulls from bare
     * gather (different XP / progression signal in the future).
     */
    data class ResourceExtracted(
        val agent: AgentId,
        val at: NodeId,
        val item: ItemId,
        val quantity: Int,
        override val tick: Long,
        val causedBy: UUID,
    ) : EconomyEvent

    /**
     * Emitted by the trade-offer reducer once a PENDING trade row is persisted.
     * [listeners] carries both parties so the dispatcher writes the envelope to
     * the offerer (so they can correlate their own QUEUED ack) and the recipient
     * (so they learn an offer is waiting). The trade itself is keyed by [tradeId]
     * — agents use this id when calling `trade_respond`.
     */
    data class TradeOfferReceived(
        val offerer: AgentId,
        val recipient: AgentId,
        val tradeId: UUID,
        val at: NodeId,
        val offered: Map<ItemId, Int>,
        val requested: Map<ItemId, Int>,
        val listeners: Set<AgentId>,
        override val tick: Long,
        val causedBy: UUID,
    ) : EconomyEvent

    /**
     * Emitted by the trade-respond reducer when the recipient accepted and both
     * inventories were swapped on the same tick. Routed to both parties so the
     * offerer learns the swap happened and the recipient sees the deterministic
     * outcome.
     */
    data class TradeAccepted(
        val offerer: AgentId,
        val recipient: AgentId,
        val tradeId: UUID,
        val offered: Map<ItemId, Int>,
        val requested: Map<ItemId, Int>,
        val listeners: Set<AgentId>,
        override val tick: Long,
        val causedBy: UUID,
    ) : EconomyEvent

    /** Emitted by the trade-respond reducer when the recipient rejected the offer. */
    data class TradeRejected(
        val offerer: AgentId,
        val recipient: AgentId,
        val tradeId: UUID,
        val listeners: Set<AgentId>,
        override val tick: Long,
        val causedBy: UUID,
    ) : EconomyEvent
}
