package dev.gvart.genesara.world.events

import dev.gvart.genesara.player.AbilityCostResource
import dev.gvart.genesara.player.AbilityEffectKind
import dev.gvart.genesara.player.AbilityId
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.PerkId
import dev.gvart.genesara.player.TriggeredPassiveEffectKind
import dev.gvart.genesara.player.TriggeredPassiveTrigger
import dev.gvart.genesara.world.BodyDelta
import dev.gvart.genesara.world.BuildingType
import dev.gvart.genesara.world.CropId
import dev.gvart.genesara.world.DamageType
import dev.gvart.genesara.world.DroppedItemView
import dev.gvart.genesara.world.Gauge
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.NpcId
import dev.gvart.genesara.world.NpcType
import dev.gvart.genesara.world.Rarity
import dev.gvart.genesara.world.RecipeId
import dev.gvart.genesara.world.SayChannel
import dev.gvart.genesara.world.SpeechMode
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
        /** Stamina actually charged for this step after terrain, road, and speed-scaling adjustments. */
        val staminaSpent: Int,
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
    ) : WorldEvent

    /** The terminal step landed — the building flipped to ACTIVE on this tick. */
    data class BuildingConstructed(
        val agent: AgentId,
        val instanceId: UUID,
        val type: BuildingType,
        val at: NodeId,
        val totalSteps: Int,
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
     * `causedBy` is null for starvation deaths (the sweep is not a queued
     * command). Combat deaths populate it with the killing attack's
     * commandId, mirroring [AgentDied.causedBy].
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

    /**
     * Outcome of one [dev.gvart.genesara.world.commands.WorldCommand.AttackTarget].
     * Always emitted on a successful attack reducer run, including when the target
     * dodged ([hpLost] = 0). [targetKilled] is a convenience: a paired
     * [AgentDied] event lands at the same tick when true. Routed to BOTH attacker
     * and target streams by the dispatcher so each can correlate.
     */
    data class AgentAttacked(
        val attacker: AgentId,
        val target: AgentId,
        val at: NodeId,
        val damageType: DamageType,
        /**
         * Damage after attacker scaling but before crit, dodge, and armor mitigation:
         * `weaponPower × stat × damageTypeMod × (1 + skill scaling bonus)`. Armor will
         * subtract from this in a future combat slice.
         */
        val baseDamage: Int,
        /** Actual HP subtracted from the target. 0 on dodge. */
        val hpLost: Int,
        val isCrit: Boolean,
        val isDodged: Boolean,
        val targetHpAfter: Int,
        val targetKilled: Boolean,
        override val tick: Long,
        val causedBy: UUID,
    ) : WorldEvent

    /** `target` is set only for combat triggers (OnHit*, OnCrit, OnKill, OnDodge). */
    data class PerkTriggered(
        val agent: AgentId,
        val perkId: PerkId,
        val trigger: TriggeredPassiveTrigger,
        val effectKind: TriggeredPassiveEffectKind,
        val params: Map<String, String>,
        val target: AgentId?,
        override val tick: Long,
        val causedBy: UUID?,
    ) : WorldEvent

    /**
     * Outcome of a successful [dev.gvart.genesara.world.commands.WorldCommand.UseAbility].
     * The perk cooldown is now armed until [readyAtTick]; for `SCALE_NEXT_ATTACK`
     * the buff lives on the agent until consumed by the next AttackTarget reducer.
     */
    data class AbilityUsed(
        val agent: AgentId,
        val perkId: PerkId,
        val abilityId: AbilityId,
        val target: AgentId?,
        val effectKind: AbilityEffectKind,
        val params: Map<String, String>,
        val costResource: AbilityCostResource,
        val costAmount: Int,
        val readyAtTick: Long,
        override val tick: Long,
        val causedBy: UUID,
    ) : WorldEvent

    /** Body cache caught up to attribute-derived pool maxima after an out-of-tick mutation
     *  (`allocate_points` today). Current pool values clamp to the new max but are not refilled. */
    data class DerivedPoolsRefreshed(
        val agent: AgentId,
        val maxHp: Int,
        val maxStamina: Int,
        val maxMana: Int,
        override val tick: Long,
        val causedBy: UUID,
    ) : WorldEvent

    /**
     * Emitted by the [dev.gvart.genesara.world.commands.WorldCommand.Say] reducer with
     * the deterministic set of [listeners] resolved at the reducer's tick (every agent
     * within [mode]'s hop radius of [at], including the speaker for self-hearing). The
     * dispatcher fans this single event out by iterating [listeners] and writing one
     * `agent.spoke` envelope per listener — same shape as [PassivesApplied].
     */
    data class AgentSpoke(
        val speaker: AgentId,
        val at: NodeId,
        val message: String,
        val mode: SpeechMode,
        val channel: SayChannel,
        val listeners: Set<AgentId>,
        override val tick: Long,
        val causedBy: UUID,
    ) : WorldEvent

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
    ) : WorldEvent

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
    ) : WorldEvent

    /** Emitted by the trade-respond reducer when the recipient rejected the offer. */
    data class TradeRejected(
        val offerer: AgentId,
        val recipient: AgentId,
        val tradeId: UUID,
        val listeners: Set<AgentId>,
        override val tick: Long,
        val causedBy: UUID,
    ) : WorldEvent

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
    ) : WorldEvent

    /** Tend reducer fired: a planted plot's neglect timer was refreshed. */
    data class CropTended(
        val agent: AgentId,
        val at: NodeId,
        val plotId: UUID,
        val crop: CropId,
        override val tick: Long,
        val causedBy: UUID,
    ) : WorldEvent

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
    ) : WorldEvent

    /**
     * Per-tick decay sweep cleared a planted plot whose `last_tended_at_tick`
     * fell behind the crop's neglect window. `causedBy` is null because the
     * sweep runs without a queued command — same shape as the starvation
     * branch of [AgentDied].
     */
    data class CropDied(
        val agent: AgentId,
        val at: NodeId,
        val plotId: UUID,
        val crop: CropId,
        val neglectedSinceTick: Long,
        override val tick: Long,
    ) : WorldEvent

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
    ) : WorldEvent

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
    ) : WorldEvent

    /**
     * Emitted when the build reducer auto-issues a GATE_KEY on gate build
     * completion, AND when the `copy_gate_key` reducer mints a duplicate.
     * [byCopy] distinguishes the two — auto-issue is implicit on
     * [BuildingConstructed]; copies are caused by [WorldCommand.CopyGateKey].
     */
    data class GateKeyMinted(
        val agent: AgentId,
        val keyInstanceId: UUID,
        val gateId: UUID,
        val byCopy: Boolean,
        override val tick: Long,
        val causedBy: UUID,
    ) : WorldEvent

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
    ) : WorldEvent

    /**
     * NPC AI sweep fired an attack at [target]. Mirrors [AgentAttacked] for
     * agent-vs-NPC observability — the deferral note in PR #N tracks unifying
     * these via `CombatantAttacked`.
     */
    data class NpcAttackedAgent(
        val npc: NpcId,
        val npcType: NpcType,
        val target: AgentId,
        val at: NodeId,
        val damageType: DamageType,
        val baseDamage: Int,
        val hpLost: Int,
        val isDodged: Boolean,
        val targetHpAfter: Int,
        val targetKilled: Boolean,
        override val tick: Long,
    ) : WorldEvent

    /**
     * Agent attacked a Tier-A NPC via the `attack_npc` MCP tool. Carries the
     * full damage record for the attacker's stream and any spectator in the
     * vision radius of either combatant's node.
     */
    data class AgentAttackedNpc(
        val attacker: AgentId,
        val npc: NpcId,
        val npcType: NpcType,
        val at: NodeId,
        val damageType: DamageType,
        val baseDamage: Int,
        val hpLost: Int,
        val isCrit: Boolean,
        val isDodged: Boolean,
        val npcHpAfter: Int,
        val npcKilled: Boolean,
        override val tick: Long,
        val causedBy: UUID,
    ) : WorldEvent

    /**
     * NPC HP hit zero. [killedBy] populated when an agent's attack landed the
     * killing blow; null when an NPC died from any future non-agent cause
     * (none today). [drops] mirrors the loot deposited to the ground; each
     * entry also rides as a paired [ItemDroppedOnGround] so existing pickup
     * consumers see the corpse pile without learning a new event.
     */
    data class NpcDied(
        val npc: NpcId,
        val npcType: NpcType,
        val at: NodeId,
        val killedBy: AgentId?,
        val drops: List<DroppedItemView>,
        override val tick: Long,
        val causedBy: UUID?,
    ) : WorldEvent

    /** PASSIVE NPC fled one node away from an attacker. */
    data class NpcMoved(
        val npc: NpcId,
        val npcType: NpcType,
        val from: NodeId,
        val to: NodeId,
        override val tick: Long,
    ) : WorldEvent
}
