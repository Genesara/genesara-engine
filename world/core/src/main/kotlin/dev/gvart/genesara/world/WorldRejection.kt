package dev.gvart.genesara.world

import dev.gvart.genesara.player.AbilityCostResource
import dev.gvart.genesara.player.AbilityId
import dev.gvart.genesara.player.AbilityTarget
import dev.gvart.genesara.player.AgentId
import java.util.UUID

sealed interface WorldRejection {
    data class UnknownRegion(val region: RegionId) : WorldRejection
    data class UnknownNode(val node: NodeId) : WorldRejection
    data class UnknownProfile(val agent: AgentId) : WorldRejection
    data class UnknownItem(val item: ItemId) : WorldRejection
    data class AlreadySpawned(val agent: AgentId) : WorldRejection
    data class NotAdjacent(val from: NodeId, val to: NodeId) : WorldRejection
    data class NotEnoughStamina(
        val agent: AgentId,
        val required: Int,
        val available: Int,
    ) : WorldRejection
    /** The destination region has no biome or climate set yet (admin hasn't painted it). */
    data class UnpaintedRegion(val region: RegionId) : WorldRejection
    /** Agent attempted a presence-bound action while not in the world. */
    data class NotInWorld(val agent: AgentId) : WorldRejection
    /**
     * The agent's current node has no live deposit for [item]. Two underlying causes
     * collapse to this single rejection:
     *
     *  - The terrain has no spawn rule for the item (wrong biome / wrong terrain
     *    altogether).
     *  - The terrain has a rule with `spawn-chance < 1.0` and this specific node lost
     *    the roll at world-paint time.
     *
     * Either way the agent's response is the same: try a different node (possibly a
     * different terrain). [NodeResourceDepleted] is reserved for "this node had it,
     * mined out" — a different signal an agent can react to (wait for regen / move on).
     */
    data class ResourceNotAvailableHere(
        val agent: AgentId,
        val node: NodeId,
        val item: ItemId,
    ) : WorldRejection
    /**
     * The node had a deposit of [item] but its live quantity is zero. Different from
     * [ResourceNotAvailableHere] because the deposit may regenerate (organic items)
     * or stay gone forever (ore / stone / coal / gem / salt) — strategic responses
     * differ accordingly.
     */
    data class NodeResourceDepleted(
        val agent: AgentId,
        val node: NodeId,
        val item: ItemId,
    ) : WorldRejection
    /** Agent tried to consume / use an item they don't own. */
    data class ItemNotInInventory(val agent: AgentId, val item: ItemId) : WorldRejection
    /** Agent tried to consume an item that has no consumable effect (e.g. WOOD). */
    data class ItemNotConsumable(val item: ItemId) : WorldRejection
    /** Agent tried to `drink` on a terrain not tagged as a water source (e.g. FOREST). */
    data class NotAWaterSource(val agent: AgentId, val node: NodeId) : WorldRejection
    /**
     * Agent tried to `move` onto a tile whose terrain is non-traversable (e.g. OCEAN
     * before boats unlock in Phase 3, or CLIFFSIDE). Different from [NotAdjacent]
     * because the tile is reachable on the graph, just not enterable on foot.
     */
    data class TerrainNotTraversable(
        val agent: AgentId,
        val node: NodeId,
        val terrain: Terrain,
    ) : WorldRejection

    /**
     * Agent called the `respawn` MCP tool while not actually dead — either their
     * body is above 0 HP, or they're still positioned in the world. Distinct
     * from [NotInWorld] (which is "not spawned at all") because a dead agent IS
     * unpositioned, but for a different reason — surfacing both lets the agent
     * branch correctly.
     */
    data class NotDead(val agent: AgentId) : WorldRejection

    /**
     * Respawn resolved to no spawnable site at all — the agent has no
     * checkpoint, the race has no starter node, and `randomSpawnableNode()`
     * returned null (a misconfigured world with zero traversable nodes).
     * Distinct from [UnknownNode] which means a specific node id is gone.
     */
    data class NoSpawnableNode(val agent: AgentId) : WorldRejection

    data class OverEncumbered(
        val agent: AgentId,
        val requested: Int,
        val capacity: Int,
    ) : WorldRejection

    /**
     * Agent submitted a build step but lacks at least one material this step costs.
     * The reducer surfaces the FIRST missing material so an agent that's short on
     * multiple ingredients gets a deterministic, actionable error rather than a
     * synthetic aggregate.
     */
    data class InsufficientMaterials(
        val agent: AgentId,
        val type: BuildingType,
        val item: ItemId,
        val required: Int,
        val available: Int,
    ) : WorldRejection

    /**
     * Agent submitted a build step but their level in the building's required skill is
     * below the catalog's `requiredSkillLevel`. Surfaced only when the gate is non-zero
     * (Tier-1 v1 buildings have level 0 — basic actions skill-free).
     */
    data class BuildingSkillTooLow(
        val agent: AgentId,
        val type: BuildingType,
        val skill: dev.gvart.genesara.player.SkillId,
        val required: Int,
        val current: Int,
    ) : WorldRejection

    /**
     * Agent tried to lay a fresh foundation for [type] at [node], but another
     * non-destroyed instance of the same type already exists there (either
     * UNDER_CONSTRUCTION by any agent or ACTIVE). At most one instance per
     * (buildingType, node) pair is allowed. Surfaced only on the foundation step;
     * advancing an agent's own existing in-progress build is unaffected.
     */
    data class DuplicateBuildingAtNode(
        val agent: AgentId,
        val type: BuildingType,
        val node: NodeId,
        val existingInstanceId: java.util.UUID,
    ) : WorldRejection

    /**
     * Agent submitted a `build` call on a multi-bar building without specifying which
     * skill-bar to advance. Multi-bar buildings (Tier-2: WATCHTOWER, STABLE) require
     * the lead to declare the bar each step; single-bar buildings auto-default.
     */
    data class SkillRequiredForMultiBar(
        val agent: AgentId,
        val type: BuildingType,
    ) : WorldRejection

    /**
     * Agent submitted a `build` call naming a skill that is not one of the building's
     * declared bars. E.g., trying `build(WATCHTOWER, ALCHEMY)` — ALCHEMY isn't a bar of
     * a watchtower, so the call is rejected before any side-effect.
     */
    data class BarNotInBuilding(
        val agent: AgentId,
        val type: BuildingType,
        val skill: dev.gvart.genesara.player.SkillId,
    ) : WorldRejection

    /**
     * Agent submitted a `build` call on a bar that is already at its declared total
     * steps. The other bars of a multi-bar building may still need work; the lead
     * should call `build` with one of the unfilled skills instead.
     */
    data class BarAlreadyComplete(
        val agent: AgentId,
        val type: BuildingType,
        val skill: dev.gvart.genesara.player.SkillId,
    ) : WorldRejection

    /** Chest reducer target id does not resolve to any building row. */
    data class BuildingNotFound(val building: java.util.UUID) : WorldRejection

    /** Chest reducer target is a building, but it is not yet ACTIVE — operations are gated to completed structures. */
    data class BuildingNotActive(val building: java.util.UUID, val status: BuildingStatus) : WorldRejection

    /** Agent attempted to interact with a building whose node they are not standing on. */
    data class NotOnBuildingNode(val agent: AgentId, val building: java.util.UUID) : WorldRejection

    /**
     * Agent attempted to deposit / withdraw against a chest they do not own. Phase 1
     * chests are owner-only; clan-shared chests will land as a separate building type
     * with its own access rules in Phase 3.
     */
    data class NotChestOwner(val agent: AgentId, val chest: java.util.UUID) : WorldRejection

    /** Deposit would push the chest above its per-type weight cap. */
    data class ChestCapacityExceeded(
        val chest: java.util.UUID,
        val attemptedGrams: Int,
        val capacityGrams: Int,
    ) : WorldRejection

    /** Withdraw asked for more of [item] than the chest currently holds. */
    data class ChestDoesNotContain(
        val chest: java.util.UUID,
        val item: ItemId,
        val requested: Int,
        val available: Int,
    ) : WorldRejection

    /**
     * Reducer received a quantity of zero or less. The MCP boundary is the right place
     * to filter this, but the reducer also rejects defensively so a buggy direct caller
     * cannot crash the entire tick by tripping `require`.
     */
    data class NonPositiveQuantity(val agent: AgentId, val quantity: Int) : WorldRejection

    /** Craft target id is not in the recipe catalog. */
    data class UnknownRecipe(val recipe: RecipeId) : WorldRejection

    /**
     * Agent's level in the recipe's required skill is below the catalog gate. Surfaced
     * only when the gate is non-zero (T1 recipes ship with level 0 — basic crafting is
     * skill-free entry).
     */
    data class CraftSkillTooLow(
        val agent: AgentId,
        val recipe: RecipeId,
        val skill: dev.gvart.genesara.player.SkillId,
        val required: Int,
        val current: Int,
    ) : WorldRejection

    /**
     * Recipe declares a [BuildingCategoryHint] station, but the agent's current node has
     * no ACTIVE building of that category. The hint is the same one consumed by movement
     * and drink reducers, so a single recipe can be satisfied by any building variant
     * that maps to the hint (e.g. CRAFTING_STATION_METAL → FORGE today, future variants
     * later).
     */
    data class RecipeRequiresStation(
        val agent: AgentId,
        val recipe: RecipeId,
        val node: NodeId,
        val station: BuildingCategoryHint,
    ) : WorldRejection

    /**
     * Agent submitted a craft but lacks at least one of the recipe's input materials.
     * The reducer surfaces the FIRST missing input so an agent short on multiple
     * ingredients gets a deterministic, actionable error rather than a synthetic
     * aggregate — same contract as [InsufficientMaterials] for buildings.
     */
    data class InsufficientCraftMaterials(
        val agent: AgentId,
        val recipe: RecipeId,
        val item: ItemId,
        val required: Int,
        val available: Int,
    ) : WorldRejection

    /**
     * Agent's existing stack of [item] is at or near `maxStack`, and adding [incoming]
     * would push it above the cap. Surfaced by stackable verbs (`craft` today;
     * `harvest` later — see its open TODO) so the agent can deposit / consume / drop
     * the surplus before retrying.
     */
    data class StackFull(
        val agent: AgentId,
        val item: ItemId,
        val current: Int,
        val incoming: Int,
        val maxStack: Int,
    ) : WorldRejection

    /**
     * Pickup target dropId is not at the agent's node. Two underlying causes
     * collapse to this single rejection:
     *
     *  - The dropId never existed on this node (agent submitted a stale id).
     *  - Another agent on the same tick raced to the same drop and won the
     *    atomic [GroundItemStore.take]; the second caller sees null.
     *
     * The agent's strategic response is the same in both cases: re-read the
     * node via `look_around` and pick a different drop.
     */
    data class GroundItemNoLongerAvailable(
        val agent: AgentId,
        val dropId: UUID,
    ) : WorldRejection

    /** Attack target id is the same agent calling the attack — self-strike rejected. */
    data class CannotAttackSelf(val agent: AgentId) : WorldRejection

    /** Attack target is not currently positioned in the world (never spawned, despawned, or dead and unspawned). */
    data class TargetNotInWorld(val attacker: AgentId, val target: AgentId) : WorldRejection

    /**
     * Target sits beyond the wielded weapon's [Item.range] (or the unarmed
     * fallback range when no weapon is equipped). Carries the weapon's reach
     * so the agent can decide whether to close the gap or pick a longer-range
     * weapon — a melee miss against a same-region neighbor reads the same as a
     * bow shot at three nodes' distance, and that distinction matters.
     */
    data class TargetOutOfRange(
        val attacker: AgentId,
        val target: AgentId,
        val attackerAt: NodeId,
        val targetAt: NodeId,
        val weaponRange: Int,
    ) : WorldRejection

    /**
     * Attack target's body is at HP=0 — already-dead agents await respawn and
     * cannot be re-attacked. Distinct from [TargetNotInWorld]; this surfaces
     * only on the rare race where the death sweep hasn't yet removed them.
     */
    data class TargetAlreadyDead(val attacker: AgentId, val target: AgentId) : WorldRejection

    /**
     * Agent does not have a chosen perk granting [ability] — either no perk maps
     * to this id, or the perk's parent skill is not in a slot. Distinguishing the
     * two would leak the catalog (`ActivePerkLookup` deliberately collapses both
     * to null), so the rejection collapses too.
     */
    data class UnknownAbility(val agent: AgentId, val ability: AbilityId) : WorldRejection

    /** Ability is on internal cooldown; [readyAtTick] is the earliest tick it can be cast again. */
    data class AbilityOnCooldown(
        val agent: AgentId,
        val ability: AbilityId,
        val readyAtTick: Long,
    ) : WorldRejection

    /** Ability requires [resource] at [required], agent has [available]. */
    data class InsufficientAbilityResource(
        val agent: AgentId,
        val ability: AbilityId,
        val resource: AbilityCostResource,
        val required: Int,
        val available: Int,
    ) : WorldRejection

    /**
     * Target shape mismatch — the supplied/missing target does not match the
     * ability's [expected] shape (e.g. SINGLE_AGENT requires a target id;
     * SELF / AREA_SELF_NODE forbid one).
     */
    data class AbilityTargetMismatch(
        val agent: AgentId,
        val ability: AbilityId,
        val expected: AbilityTarget,
    ) : WorldRejection

    /** SINGLE_AGENT ability target is not in the caster's node. */
    data class AbilityTargetNotInSameNode(
        val agent: AgentId,
        val ability: AbilityId,
        val target: AgentId,
    ) : WorldRejection

    /**
     * Speaker's message length exceeds [max]. Surfaced by the `say` reducer so an agent
     * who pasted a wall of text gets a deterministic, actionable error rather than a
     * silent truncation.
     */
    data class MessageTooLong(
        val agent: AgentId,
        val length: Int,
        val max: Int,
    ) : WorldRejection

    /** Trade offer or respond submitted with the same agent on both sides. */
    data class CannotTradeWithSelf(val agent: AgentId) : WorldRejection

    /** Trade offer carries no items on either side — nothing to swap. */
    data class TradeOfferEmpty(val agent: AgentId) : WorldRejection

    /**
     * Offerer and recipient are not co-located. Enforced at both offer and respond
     * time — the parties must remain on the same node between submitting and
     * resolving. Carries both positions so the offerer can decide whether to chase
     * or abort.
     */
    data class TradePartnerNotInSameNode(
        val actor: AgentId,
        val partner: AgentId,
        val actorAt: NodeId,
        val partnerAt: NodeId,
    ) : WorldRejection

    /** Respond reducer target tradeId does not resolve to any row. */
    data class TradeNotFound(val tradeId: UUID) : WorldRejection

    /**
     * Respond target exists but is no longer PENDING — already accepted, already
     * rejected, or won by a concurrent respond on the same tick. Carries the
     * terminal status so the responder can tell what happened.
     */
    data class TradeNotPending(val tradeId: UUID, val currentStatus: TradeStatus) : WorldRejection

    /** Respond was submitted by an agent who is not the trade's recipient. */
    data class NotTradeRecipient(val actor: AgentId, val tradeId: UUID) : WorldRejection

    /**
     * High-value trade ([value] above [valueThreshold]) attempted between a pair
     * whose relationship score is below [relationshipThreshold]. The trust gate
     * blocks strangers from draining each other in one swap.
     */
    data class InsufficientTrust(
        val offerer: AgentId,
        val recipient: AgentId,
        val value: Int,
        val valueThreshold: Int,
        val relationshipScore: Int,
        val relationshipThreshold: Int,
    ) : WorldRejection

    /** Plant / tend / harvest target plotId does not resolve to any row. */
    data class UnknownPlot(val agent: AgentId, val plotId: UUID) : WorldRejection

    /** Cultivation reducer target crop id is not in the crop catalog. */
    data class UnknownCrop(val agent: AgentId, val crop: CropId) : WorldRejection

    /** Agent attempted a plot action while not standing on the plot's node. */
    data class NotOnPlotNode(
        val agent: AgentId,
        val plotId: UUID,
        val agentAt: NodeId,
        val plotAt: NodeId,
    ) : WorldRejection

    /** Plant submitted against a plot that already carries a crop. */
    data class PlotNotEmpty(val agent: AgentId, val plotId: UUID, val planted: CropId) : WorldRejection

    /** Tend or harvest submitted against an empty plot. */
    data class PlotEmpty(val agent: AgentId, val plotId: UUID) : WorldRejection

    /**
     * Plant submitted on a plot whose terrain is not in the crop's
     * `requiredTerrain` set. Carries the actual terrain and the allowed set
     * so the agent can pick a different plot or a different crop.
     */
    data class CropTerrainMismatch(
        val agent: AgentId,
        val plotId: UUID,
        val crop: CropId,
        val terrain: Terrain,
        val allowed: Set<Terrain>,
    ) : WorldRejection

    /** Agent's FARMING level is below the crop's `requiredFarmingLevel` gate. */
    data class CropFarmingLevelTooLow(
        val agent: AgentId,
        val crop: CropId,
        val required: Int,
        val current: Int,
    ) : WorldRejection

    /** Plant requires the crop's seed item; the agent's inventory does not carry one. */
    data class MissingSeed(
        val agent: AgentId,
        val crop: CropId,
        val seedItem: ItemId,
    ) : WorldRejection

    /**
     * Harvest submitted against a planted plot whose growth is not yet
     * complete. Carries the remaining ticks so the agent can budget the
     * wait or switch tasks. A non-positive value would not be a rejection.
     */
    data class CropNotRipe(
        val agent: AgentId,
        val plotId: UUID,
        val crop: CropId,
        val ticksRemaining: Long,
    ) : WorldRejection

    /**
     * `build` rejected because another DEFENSIVE-category structure already
     * exists at the agent's node. WOODEN_WALL and GATE share the slot:
     * stacking them is nonsensical (a gate is bypassable, a wall isn't).
     * Carries the existing variant so the agent can decide whether to
     * demolish-and-rebuild or pick a different node.
     */
    data class DefensiveAlreadyAtNode(
        val agent: AgentId,
        val type: BuildingType,
        val node: NodeId,
        val existingType: BuildingType,
        val existingInstanceId: UUID,
    ) : WorldRejection

    /**
     * `build` rejected because the requested [type] is terrain-coupled and
     * the agent's node is not on the allowed list. Today only MINE is
     * terrain-coupled (FOOTHILLS / MOUNTAIN / VOLCANIC); future variants
     * will reuse this rejection.
     */
    data class BuildingTerrainMismatch(
        val agent: AgentId,
        val type: BuildingType,
        val node: NodeId,
        val terrain: Terrain,
        val allowed: Set<Terrain>,
    ) : WorldRejection

    /**
     * `toggle_gate` rejected because the gate id does not resolve, the
     * building is not a GATE, or it is not ACTIVE. Surfaced as a single
     * rejection so probing for "is X a gate?" via id enumeration cannot
     * leak the building catalog.
     */
    data class GateNotFound(val agent: AgentId, val gateId: UUID) : WorldRejection

    /**
     * `toggle_gate` rejected because the agent does not hold any key bound
     * to [gateId]. The reducer surfaces this without disclosing whether
     * other agents hold keys — that knowledge is per-agent.
     */
    data class MissingGateKey(val agent: AgentId, val gateId: UUID) : WorldRejection

    /**
     * Movement onto a node with an ACTIVE wall (WOODEN_WALL) or a CLOSED
     * GATE. The reducer collapses both to this single rejection so an agent
     * probing via repeated moves cannot deduce the precise variant.
     */
    data class DefensiveBlocks(val agent: AgentId, val node: NodeId) : WorldRejection

    /**
     * `extract` rejected because no ACTIVE MINE is at the agent's node.
     * Distinct from [RecipeRequiresStation] because extract is not a craft
     * verb — it has its own surface area and rejection.
     */
    data class ExtractRequiresMine(val agent: AgentId, val node: NodeId) : WorldRejection

    /**
     * `harvest` rejected because the [item] is flagged `extractionOnly` —
     * it requires the `extract` verb at a built MINE. Surfaced so an agent
     * who learns of GOLD / ORE / COAL on a node can route to `extract`
     * instead of retrying `harvest`.
     */
    data class HarvestRequiresExtraction(
        val agent: AgentId,
        val node: NodeId,
        val item: ItemId,
    ) : WorldRejection

    /**
     * `craft` rejected because the recipe declares `requiresSource` and one
     * of the following holds:
     *   - The command did not pass a `source` UUID.
     *   - The supplied `source` does not resolve to an instance the agent owns.
     *   - The supplied `source`'s item-id does not match the recipe's required type.
     *
     * Collapsed for anti-probe: an agent enumerating UUIDs cannot tell the
     * three cases apart. [requiredItem] is the item type the recipe expects
     * the source to be (e.g. GATE_KEY for GATE_KEY_COPY).
     */
    data class RecipeRequiresSource(
        val agent: AgentId,
        val recipe: RecipeId,
        val requiredItem: ItemId,
    ) : WorldRejection

    /** `attack(npc:<uuid>)` referenced an NPC id that is not loaded in the active set (out of range or already dead). */
    data class UnknownNpc(val agent: AgentId, val npc: NpcId) : WorldRejection

    /** Attack target is too far for the wielded weapon's reach. */
    data class NpcOutOfRange(
        val agent: AgentId,
        val npc: NpcId,
        val attackerAt: NodeId,
        val npcAt: NodeId,
        val weaponRange: Int,
    ) : WorldRejection

    /** NPC HP is already zero — the death sweep hasn't removed it yet. */
    data class NpcAlreadyDead(val agent: AgentId, val npc: NpcId) : WorldRejection

    /** `tame` target NPC has no entry in the mounts catalog — not a tameable species. */
    data class NpcNotTameable(val agent: AgentId, val npc: NpcId) : WorldRejection

    /** Mount op referenced an unknown mount id. */
    data class UnknownMount(val agent: AgentId, val mount: MountId) : WorldRejection

    /** `mount(mount:...)` rejected because the mount is already mounted by another agent. */
    data class MountAlreadyMounted(val agent: AgentId, val mount: MountId, val rider: AgentId) : WorldRejection

    /** Mount operation referenced a mount not at the same node as the agent. */
    data class MountNotAtSameNode(
        val agent: AgentId,
        val mount: MountId,
        val agentAt: NodeId,
        val mountAt: NodeId,
    ) : WorldRejection

    /**
     * Mounted-agent guard. Fires when a verb cannot be performed while the
     * agent is riding a mount (or — special case — when a rider tries to
     * attack the very mount they're sitting on).
     *
     * Today: `tame` (TameReducer) and `attack(self-mount)` (AttackMountReducer).
     * Open follow-up (I8): widen to harvest, extract, cultivate, craft, build,
     * pickup once those reducers get the same `MountInstanceStore.findByRider`
     * check.
     */
    data class MountedActionNotAllowed(val agent: AgentId, val verb: String) : WorldRejection

    /** `mount(mount:...)` while the agent is already on a different mount. */
    data class AlreadyMounted(val agent: AgentId, val currentMount: MountId) : WorldRejection

    /** `dismount()` while the agent is not on any mount. */
    data class NotMounted(val agent: AgentId) : WorldRejection

    /**
     * Mount couldn't be acted on — either HP was already 0 at read time, or a
     * concurrent writer (maintenance sweep, parallel combat reducer) deleted
     * the row between this command's read and write. Both cases collapse to
     * the same agent-visible outcome: the mount isn't available to act on.
     * Idempotent retry is safe (a phantom kill returns this; a real kill
     * surfaces via the MountDied event from whoever won the race).
     */
    data class MountAlreadyDead(val agent: AgentId, val mount: MountId) : WorldRejection

    /** Mounted-move fatigue insufficient. */
    data class NotEnoughMountFatigue(
        val agent: AgentId,
        val mount: MountId,
        val required: Int,
        val available: Int,
    ) : WorldRejection

    /** `maintain` resource isn't tagged, or its maintenance type doesn't match the target's accepted type. */
    data class IncompatibleMaintenanceResource(
        val agent: AgentId,
        val mount: MountId,
        val item: ItemId,
    ) : WorldRejection
}
