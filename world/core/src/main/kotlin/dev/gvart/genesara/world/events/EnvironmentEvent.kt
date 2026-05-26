package dev.gvart.genesara.world.events

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.BuildingStatus
import dev.gvart.genesara.world.BuildingType
import dev.gvart.genesara.world.DroppedItemView
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.MountId
import dev.gvart.genesara.world.MountType
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

    /** A `tame` attempt succeeded: the [npc] row is deleted, [mount] inserted, owned by [agent]. */
    data class MountTamed(
        val agent: AgentId,
        val npc: NpcId,
        val mount: MountId,
        val mountType: MountType,
        val at: NodeId,
        val rolledChancePercent: Int,
        override val tick: Long,
        val causedBy: UUID,
    ) : EnvironmentEvent

    /**
     * A `tame` attempt failed the chance roll. [spooked] is true when the
     * post-failure spook roll also hit and the NPC fled an adjacent node —
     * agents use this signal to relocate before retrying.
     */
    data class MountTameFailed(
        val agent: AgentId,
        val npc: NpcId,
        val at: NodeId,
        val rolledChancePercent: Int,
        val spooked: Boolean,
        override val tick: Long,
        val causedBy: UUID,
    ) : EnvironmentEvent

    /** An agent mounted a transport. */
    data class TransportMounted(
        val agent: AgentId,
        val mount: MountId,
        val mountType: MountType,
        val at: NodeId,
        override val tick: Long,
        val causedBy: UUID,
    ) : EnvironmentEvent

    /**
     * An agent dismounted a transport. `causedBy` is the dismount command's
     * id for an explicit dismount; null for implicit dismounts (mount death
     * via combat or starvation — see [MountDied]).
     */
    data class TransportDismounted(
        val agent: AgentId,
        val mount: MountId,
        val mountType: MountType,
        val at: NodeId,
        override val tick: Long,
        val causedBy: UUID?,
    ) : EnvironmentEvent

    /**
     * `maintain` succeeded: [restored] gauge units were applied to [target]
     * (hunger for ANIMAL mounts; future: fuel/wear/HP for vehicles/buildings)
     * by consuming [quantity] of [resource].
     */
    data class Maintained(
        val agent: AgentId,
        val target: MountId,
        val resource: ItemId,
        val quantity: Int,
        val restored: Int,
        override val tick: Long,
        val causedBy: UUID,
    ) : EnvironmentEvent

    /**
     * Mount HP hit zero. [cause] discriminates starvation vs combat death;
     * [killedBy] populated only for combat. Equipped MountGear is destroyed
     * with the mount (§16 canon); cargo drops on the ground at [at] as paired
     * `EconomyEvent.ItemDroppedOnGround` events.
     */
    data class MountDied(
        val mount: MountId,
        val mountType: MountType,
        val at: NodeId,
        val cause: MountDeathCause,
        val killedBy: AgentId? = null,
        override val tick: Long,
        val causedBy: UUID? = null,
    ) : EnvironmentEvent

    /**
     * Admin write landed on a building row — create, edit, or delete.
     * Agent-facing dispatchers MUST NOT subscribe; this event is for the
     * admin dashboard live feed only. [removed] is true for DELETE, false
     * for POST and PATCH. [changedFields] enumerates the fields the admin
     * actually wrote (post-defaults); empty for POST when every field
     * defaulted from the catalog.
     */
    data class BuildingAdminEdited(
        val instanceId: UUID,
        val type: BuildingType,
        val at: NodeId,
        val status: BuildingStatus?,
        val hpCurrent: Int?,
        val hpMax: Int?,
        val progressSteps: Int?,
        val totalSteps: Int?,
        val removed: Boolean,
        val changedFields: Set<String>,
        val byAdminId: UUID,
        override val tick: Long,
    ) : EnvironmentEvent

    /**
     * An attack landed on a mount via `attack(mount:<uuid>)`. Mirrors
     * `CombatEvent.AgentAttackedNpc` shape — agents observe the same fields
     * (damage, dodge, crit, hpAfter, killed) regardless of target species.
     */
    data class AgentAttackedMount(
        val attacker: AgentId,
        val mount: MountId,
        val mountType: MountType,
        val at: NodeId,
        val damageType: dev.gvart.genesara.world.DamageType,
        val baseDamage: Int,
        val hpLost: Int,
        val isCrit: Boolean,
        val isDodged: Boolean,
        val mountHpAfter: Int,
        val mountKilled: Boolean,
        override val tick: Long,
        val causedBy: UUID,
    ) : EnvironmentEvent
}

/** Discriminator for [EnvironmentEvent.MountDied] (`cause` field). */
enum class MountDeathCause {
    /** Hunger zero -> HP loss -> 0 in the maintenance sweep. */
    STARVATION,

    /** An AttackMount swing brought the mount to 0 HP. */
    COMBAT,
}
