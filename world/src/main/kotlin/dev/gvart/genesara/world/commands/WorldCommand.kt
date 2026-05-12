package dev.gvart.genesara.world.commands

import dev.gvart.genesara.player.AbilityId
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.BuildingType
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.RecipeId
import dev.gvart.genesara.world.SayChannel
import dev.gvart.genesara.world.SpeechMode
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import java.util.UUID

/**
 * Each `@JsonSubTypes.Type.name` below is the wire-format discriminator written to the
 * Redis per-world command queue. The strings are a stable contract: renaming the Kotlin
 * class is fine, changing a discriminator silently corrupts in-flight queues across pods.
 *
 * Subtypes carrying enum fields (e.g. [BuildStructure.type] → [BuildingType]) extend the
 * wire contract by their enum constant names — renaming `STORAGE_CHEST` is equivalent to
 * changing a discriminator.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@type")
@JsonSubTypes(
    JsonSubTypes.Type(value = WorldCommand.SpawnAgent::class, name = "spawn"),
    JsonSubTypes.Type(value = WorldCommand.MoveAgent::class, name = "move"),
    JsonSubTypes.Type(value = WorldCommand.UnspawnAgent::class, name = "unspawn"),
    JsonSubTypes.Type(value = WorldCommand.Harvest::class, name = "harvest"),
    JsonSubTypes.Type(value = WorldCommand.ConsumeItem::class, name = "consume"),
    JsonSubTypes.Type(value = WorldCommand.Drink::class, name = "drink"),
    JsonSubTypes.Type(value = WorldCommand.SetSafeNode::class, name = "setSafeNode"),
    JsonSubTypes.Type(value = WorldCommand.Respawn::class, name = "respawn"),
    JsonSubTypes.Type(value = WorldCommand.BuildStructure::class, name = "build"),
    JsonSubTypes.Type(value = WorldCommand.DepositToChest::class, name = "depositToChest"),
    JsonSubTypes.Type(value = WorldCommand.WithdrawFromChest::class, name = "withdrawFromChest"),
    JsonSubTypes.Type(value = WorldCommand.CraftItem::class, name = "craft"),
    JsonSubTypes.Type(value = WorldCommand.Pickup::class, name = "pickup"),
    JsonSubTypes.Type(value = WorldCommand.AttackTarget::class, name = "attack"),
    JsonSubTypes.Type(value = WorldCommand.UseAbility::class, name = "useAbility"),
    JsonSubTypes.Type(value = WorldCommand.RefreshDerivedPools::class, name = "refreshDerivedPools"),
    JsonSubTypes.Type(value = WorldCommand.Say::class, name = "say"),
    JsonSubTypes.Type(value = WorldCommand.TradeOffer::class, name = "tradeOffer"),
    JsonSubTypes.Type(value = WorldCommand.TradeRespond::class, name = "tradeRespond"),
)
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
     * catalog entry (`Item.harvestSkill`) selects which skill is trained, if any.
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

    /**
     * Take a ground item identified by [dropId] off the agent's current node.
     * Atomic with concurrent pickups — only the first agent on the same tick
     * succeeds; subsequent ones get [WorldRejection.GroundItemNoLongerAvailable].
     * Stackable drops land in the agent's inventory; equipment drops land in
     * the equipment store unequipped (slot null) — the agent must call `equip`
     * separately to slot them.
     */
    data class Pickup(
        override val agent: AgentId,
        val dropId: UUID,
        override val commandId: UUID = UUID.randomUUID(),
    ) : WorldCommand

    /**
     * Single melee/ranged attack. The reducer reads the attacker's MAIN_HAND
     * (or unarmed defaults), rolls dodge then crit, applies the resulting damage
     * to [target]'s HP, spends stamina on the attacker, and grants weapon-skill
     * XP. A killing blow flows through [dev.gvart.genesara.world.internal.death.DeathProcessor]
     * inline so [WorldEvent.AgentDied.causedBy] carries this command's
     * [commandId]. No `ability` parameter in Slice 1 — abilities + cooldowns
     * land in a later combat slice.
     */
    data class AttackTarget(
        override val agent: AgentId,
        val target: AgentId,
        override val commandId: UUID = UUID.randomUUID(),
    ) : WorldCommand

    /**
     * Cast an active ability granted by a chosen perk on a slotted skill. The
     * [target] is required when the ability's `AbilityTarget` is `SINGLE_AGENT`,
     * forbidden when `SELF` / `AREA_SELF_NODE`. The reducer pays the resource
     * cost at cast and arms the perk cooldown; the effect resolves at this same
     * tick (a `ScaleNextAttack` buff lands on the agent and is consumed by the
     * next [AttackTarget] reducer call).
     */
    data class UseAbility(
        override val agent: AgentId,
        val ability: AbilityId,
        val target: AgentId? = null,
        override val commandId: UUID = UUID.randomUUID(),
    ) : WorldCommand

    /**
     * Push a freshly-derived pool maxima triple onto the body cache after an
     * out-of-tick mutation (e.g. `allocate_points`). The reducer copies the
     * supplied maxima onto `state.bodies[agent]` and clamps current values to
     * the new max so a max-reduction never leaves `current > max`. Currents
     * are NOT auto-restored — per the spec, allocation does not heal.
     */
    data class RefreshDerivedPools(
        override val agent: AgentId,
        val maxHp: Int,
        val maxStamina: Int,
        val maxMana: Int,
        override val commandId: UUID = UUID.randomUUID(),
    ) : WorldCommand

    /**
     * Speak [message] aloud. The reducer resolves listeners by BFS over node adjacency
     * out to [SpeechMode]'s configured radius and emits a single
     * [dev.gvart.genesara.world.events.WorldEvent.AgentSpoke] carrying the listener set;
     * the speaker is always included (self-hearing). v1 supports only [SayChannel.LOCAL]
     * — clan/trade channels land with Phase 3.
     */
    data class Say(
        override val agent: AgentId,
        val message: String,
        val mode: SpeechMode,
        val channel: SayChannel,
        override val commandId: UUID = UUID.randomUUID(),
    ) : WorldCommand

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
     */
    data class TradeOffer(
        override val agent: AgentId,
        val recipient: AgentId,
        val offered: Map<ItemId, Int>,
        val requested: Map<ItemId, Int>,
        val tradeId: UUID = UUID.randomUUID(),
        override val commandId: UUID = UUID.randomUUID(),
    ) : WorldCommand

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
    ) : WorldCommand
}
