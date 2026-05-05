package dev.gvart.genesara.world.events

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.BodyDelta
import dev.gvart.genesara.world.Building
import dev.gvart.genesara.world.DroppedItemView
import dev.gvart.genesara.world.Gauge
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.Rarity
import dev.gvart.genesara.world.RecipeId
import dev.gvart.genesara.world.WorldRejection
import java.util.UUID

sealed interface WorldEvent {
    val tick: Long

    data class AgentSpawned(
        val agent: AgentId,
        val at: NodeId,
        override val tick: Long,
        val causedBy: UUID,
    ) : WorldEvent

    data class AgentMoved(
        val agent: AgentId,
        val from: NodeId,
        val to: NodeId,
        override val tick: Long,
        val causedBy: UUID,
    ) : WorldEvent

    data class AgentDespawned(
        val agent: AgentId,
        val at: NodeId,
        override val tick: Long,
        val causedBy: UUID,
    ) : WorldEvent

    data class PassivesApplied(
        val deltas: Map<AgentId, BodyDelta>,
        override val tick: Long,
    ) : WorldEvent

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
    ) : WorldEvent

    data class ItemConsumed(
        val agent: AgentId,
        val item: ItemId,
        val gauge: Gauge,
        val refilled: Int,
        override val tick: Long,
        val causedBy: UUID,
    ) : WorldEvent

    /**
     * Emitted when an agent successfully drank from a water-source terrain. [refilled] is
     * the actual gain after clamping to maxThirst, so an already-full agent who drinks
     * still emits the event with `refilled = 0`.
     */
    data class AgentDrank(
        val agent: AgentId,
        val at: NodeId,
        val refilled: Int,
        override val tick: Long,
        val causedBy: UUID,
    ) : WorldEvent

    /**
     * Fired by the post-passives death sweep when an agent's HP hits zero. The
     * agent is removed from `state.positions` at the same tick; their body
     * persists at HP=0 until they call the `respawn` MCP tool. The penalty
     * fields summarize what the death cost — the agent uses these to know
     * whether they de-leveled or just lost some XP.
     *
     * `causedBy` is null for starvation deaths in v1 (the sweep isn't a queued
     * command). When combat ships in Phase 2, the killing-attack reducer will
     * propagate its `commandId` through to here.
     */
    data class AgentDied(
        val agent: AgentId,
        /** Node the agent was on when they died. */
        val at: NodeId,
        /** XP subtracted from the agent's character bar. 0 on the empty-bar branch. */
        val xpLost: Int,
        /** True if the agent lost a character level on the empty-bar branch. */
        val deleveled: Boolean,
        /**
         * Where the de-level penalty point came from: "UNSPENT" if from the
         * unspent-attribute pool, an attribute name (e.g. "STRENGTH") if from
         * an allocated attribute, or null when no penalty point was taken
         * (partial-bar branch, or all stats already at the floor).
         */
        val attributePointLost: String?,
        override val tick: Long,
        val causedBy: UUID?,
        /**
         * Set when the kill-streak drop-chance roll fired and the dying agent
         * had something to drop. Null when no drop happened (no streak, the
         * roll failed, or the pool was empty). The same drop shows up at the
         * death node on a paired [ItemDroppedOnGround] so other agents can
         * see and pick it up.
         */
        val droppedItem: DroppedItemView? = null,
    ) : WorldEvent

    /**
     * Fired when an agent successfully respawns after death. Mirrors
     * [AgentSpawned] in shape but includes [fromCheckpoint] so the agent can
     * tell whether their explicit safe-node binding was honored or whether
     * they fell back to the race-keyed starter (e.g. their checkpoint node
     * was deleted by an admin between death and respawn).
     */
    data class AgentRespawned(
        val agent: AgentId,
        val at: NodeId,
        /** True when respawn used the agent's set safe node, false on starter fallback. */
        val fromCheckpoint: Boolean,
        override val tick: Long,
        val causedBy: UUID,
    ) : WorldEvent

    /** Fired when an agent successfully binds their current node as their safe node. */
    data class SafeNodeSet(
        val agent: AgentId,
        val at: NodeId,
        override val tick: Long,
        val causedBy: UUID,
    ) : WorldEvent

    /** First step of a new building — the row was just inserted at progress 1, UNDER_CONSTRUCTION. */
    data class BuildingPlaced(
        val building: Building,
        override val tick: Long,
        val causedBy: UUID,
    ) : WorldEvent

    /** A non-final build step landed — the building's progress advanced but it is still UNDER_CONSTRUCTION. */
    data class BuildingProgressed(
        val building: Building,
        override val tick: Long,
        val causedBy: UUID,
    ) : WorldEvent

    /** The terminal step landed — the building flipped to ACTIVE on this tick. */
    data class BuildingCompleted(
        val building: Building,
        override val tick: Long,
        val causedBy: UUID,
    ) : WorldEvent

    /** Agent successfully transferred items from their inventory into a chest building. */
    data class ItemDeposited(
        val agent: AgentId,
        val chest: UUID,
        val item: ItemId,
        val quantity: Int,
        override val tick: Long,
        val causedBy: UUID,
    ) : WorldEvent

    /** Agent successfully transferred items from a chest building back into their inventory. */
    data class ItemWithdrawn(
        val agent: AgentId,
        val chest: UUID,
        val item: ItemId,
        val quantity: Int,
        override val tick: Long,
        val causedBy: UUID,
    ) : WorldEvent

    /**
     * Agent finished a [recipe]: inputs consumed, output produced. Equipment
     * outputs persist as a fresh `agent_equipment_instances` row with
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
    ) : WorldEvent

    /**
     * Reducer rejected the queued command at apply time. Surfaces the rejection on
     * the agent's event stream so they can react without polling. [kind] is the
     * rejection's class simple name (e.g. `"NotEnoughStamina"`, `"RecipeRequiresStation"`)
     * — agents branch on it. [rejection] carries the structured fields; Jackson
     * serializes the concrete data-class members directly.
     */
    data class CommandRejected(
        val agent: AgentId,
        val kind: String,
        val rejection: WorldRejection,
        override val tick: Long,
        val causedBy: UUID,
    ) : WorldEvent

    /**
     * Fired alongside [AgentDied] when the kill-streak drop hook produced a
     * drop. Lets agents who weren't the dying one notice that a new ground
     * item appeared at their tile — they can call `pickup` with [drop.dropId]
     * if they're standing there.
     *
     * `causedBy` is null for starvation deaths in v1 (the sweep is not a
     * queued command). Phase 2 combat will populate it with the killing
     * attack's commandId, mirroring [AgentDied.causedBy].
     */
    data class ItemDroppedOnGround(
        val at: NodeId,
        val byAgent: AgentId,
        val drop: DroppedItemView,
        override val tick: Long,
        val causedBy: UUID?,
    ) : WorldEvent

    /** Fired by the pickup reducer when an agent successfully takes a ground item. */
    data class ItemPickedUp(
        val agent: AgentId,
        val at: NodeId,
        val drop: DroppedItemView,
        override val tick: Long,
        val causedBy: UUID,
    ) : WorldEvent
}
