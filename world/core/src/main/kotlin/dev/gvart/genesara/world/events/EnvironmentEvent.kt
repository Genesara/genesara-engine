package dev.gvart.genesara.world.events

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.BuildingType
import dev.gvart.genesara.world.DroppedItemView
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.NpcId
import dev.gvart.genesara.world.NpcType
import java.util.UUID

sealed interface EnvironmentEvent : WorldEvent {

    /** A non-final build step landed — the building advanced to [step] but is still UNDER_CONSTRUCTION. */
    data class BuildingProgressed(
        val agent: AgentId,
        val instanceId: UUID,
        val type: BuildingType,
        val at: NodeId,
        val step: Int,
        val totalSteps: Int,
        override val tick: Long,
        val causedBy: UUID,
    ) : EnvironmentEvent

    /** The terminal step landed — the building flipped to ACTIVE on this tick. */
    data class BuildingConstructed(
        val agent: AgentId,
        val instanceId: UUID,
        val type: BuildingType,
        val at: NodeId,
        val totalSteps: Int,
        override val tick: Long,
        val causedBy: UUID,
    ) : EnvironmentEvent

    /** Agent successfully transferred items from their inventory into a chest building. */
    data class ItemDeposited(
        val agent: AgentId,
        val chest: UUID,
        val item: ItemId,
        val quantity: Int,
        override val tick: Long,
        val causedBy: UUID,
    ) : EnvironmentEvent

    /** Agent successfully transferred items from a chest building back into their inventory. */
    data class ItemWithdrawn(
        val agent: AgentId,
        val chest: UUID,
        val item: ItemId,
        val quantity: Int,
        override val tick: Long,
        val causedBy: UUID,
    ) : EnvironmentEvent

    /**
     * Emitted on a successful `toggle_gate`. [isOpen] is the post-toggle
     * state — agents in the same node can correlate by gate id to update
     * their cached fog-of-war about the perimeter.
     */
    data class GateToggled(
        val agent: AgentId,
        val gateId: UUID,
        val at: NodeId,
        val isOpen: Boolean,
        override val tick: Long,
        val causedBy: UUID,
    ) : EnvironmentEvent

    /**
     * Emitted when the build reducer auto-issues a GATE_KEY on gate build
     * completion, AND when the `copy_gate_key` reducer mints a duplicate.
     * [byCopy] distinguishes the two — auto-issue is implicit on
     * [BuildingConstructed]; copies are caused by a craft command (see
     * `EconomyCommand.CraftItem` with the `GATE_KEY_COPY` recipe).
     */
    data class GateKeyMinted(
        val agent: AgentId,
        val keyInstanceId: UUID,
        val gateId: UUID,
        val byCopy: Boolean,
        override val tick: Long,
        val causedBy: UUID,
    ) : EnvironmentEvent

    /**
     * Lazy-on-entry spawn fired: a Tier-A NPC was placed in [at]. `causedBy`
     * is the move command id (when an agent's arrival triggered the seed) or
     * null when the spawn was driven from somewhere else (admin tooling, etc).
     */
    data class NpcSpawned(
        val npc: NpcId,
        val npcType: NpcType,
        val at: NodeId,
        val hpMax: Int,
        override val tick: Long,
        val causedBy: UUID?,
    ) : EnvironmentEvent

    /**
     * NPC HP hit zero. [killedBy] populated when an agent's attack landed the
     * killing blow; null when an NPC died from any future non-agent cause
     * (none today). [drops] mirrors the loot deposited to the ground; each
     * entry also rides as a paired [dev.gvart.genesara.world.events.EconomyEvent.ItemDroppedOnGround]
     * so existing pickup consumers see the corpse pile without learning a new event.
     */
    data class NpcDied(
        val npc: NpcId,
        val npcType: NpcType,
        val at: NodeId,
        val killedBy: AgentId?,
        val drops: List<DroppedItemView>,
        override val tick: Long,
        val causedBy: UUID?,
    ) : EnvironmentEvent

    /** PASSIVE NPC fled one node away from an attacker. */
    data class NpcMoved(
        val npc: NpcId,
        val npcType: NpcType,
        val from: NodeId,
        val to: NodeId,
        override val tick: Long,
    ) : EnvironmentEvent
}
