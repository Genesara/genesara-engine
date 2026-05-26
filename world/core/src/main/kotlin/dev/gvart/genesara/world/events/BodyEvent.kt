package dev.gvart.genesara.world.events

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.BodyDelta
import dev.gvart.genesara.world.DroppedItemView
import dev.gvart.genesara.world.Gauge
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.NodeId
import java.util.UUID

enum class PassiveCause {
    NATURAL_REGEN,
    STARVATION,
    DEHYDRATION,
    EXHAUSTION,
}

sealed interface BodyEvent : WorldEvent {

    data class PassivesApplied(
        val deltas: Map<AgentId, BodyDelta>,
        override val tick: Long,
        /** Per-agent set of causes that drove HP loss this tick. Empty for pure regen ticks. */
        val hpLossCauses: Map<AgentId, Set<PassiveCause>> = emptyMap(),
    ) : BodyEvent

    data class ItemConsumed(
        val agent: AgentId,
        val item: ItemId,
        val gauge: Gauge,
        val refilled: Int,
        override val tick: Long,
        val causedBy: UUID,
    ) : BodyEvent

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
    ) : BodyEvent

    /**
     * Fired by the post-passives death sweep when an agent's HP hits zero. The
     * agent is removed from `state.positions` at the same tick; their body
     * persists at HP=0 until they call the `respawn` MCP tool. The penalty
     * fields summarize what the death cost — the agent uses these to know
     * whether they de-leveled or just lost some XP.
     *
     * `causedBy` is null for starvation deaths (the sweep isn't a queued
     * command). For combat deaths the attack reducer routes the killing
     * command's id through `DeathProcessor` to land here.
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
         * an allocated attribute, "AT_FLOOR" when the de-level fired but every
         * allocated stat was already at the [dev.gvart.genesara.player.AgentAttributes.MIN_ATTRIBUTE]
         * floor (no point could be taken), or null when no de-level was
         * attempted (partial-bar branch).
         */
        val attributePointLost: String?,
        override val tick: Long,
        val causedBy: UUID?,
        /**
         * Set when the kill-streak drop-chance roll fired and the dying agent
         * had something to drop. Null when no drop happened (no streak, the
         * roll failed, or the pool was empty). The same drop shows up at the
         * death node on a paired [dev.gvart.genesara.world.events.EconomyEvent.ItemDroppedOnGround]
         * so other agents can see and pick it up.
         */
        val droppedItem: DroppedItemView? = null,
    ) : BodyEvent

    /**
     * Fired when an agent successfully respawns after death. Mirrors
     * [dev.gvart.genesara.world.events.CoreEvent.AgentSpawned] in shape but includes
     * [fromCheckpoint] so the agent can tell whether their explicit safe-node binding
     * was honored or whether they fell back to the race-keyed starter (e.g. their
     * checkpoint node was deleted by an admin between death and respawn).
     */
    data class AgentRespawned(
        val agent: AgentId,
        val at: NodeId,
        /** True when respawn used the agent's set safe node, false on starter fallback. */
        val fromCheckpoint: Boolean,
        override val tick: Long,
        val causedBy: UUID,
    ) : BodyEvent

    /** Fired by the pickup reducer when an agent successfully takes a ground item. */
    data class ItemPickedUp(
        val agent: AgentId,
        val at: NodeId,
        val drop: DroppedItemView,
        override val tick: Long,
        val causedBy: UUID,
    ) : BodyEvent

    /** Body cache caught up to attribute-derived pool maxima after an out-of-tick mutation
     *  (`allocate_points` today). Current pool values clamp to the new max but are not refilled. */
    data class DerivedPoolsRefreshed(
        val agent: AgentId,
        val maxHp: Int,
        val maxStamina: Int,
        val maxMana: Int,
        override val tick: Long,
        val causedBy: UUID,
    ) : BodyEvent
}
