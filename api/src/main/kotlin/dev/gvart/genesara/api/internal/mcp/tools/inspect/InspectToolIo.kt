package dev.gvart.genesara.api.internal.mcp.tools.inspect

import com.fasterxml.jackson.annotation.JsonInclude
import dev.gvart.genesara.api.internal.mcp.tools.equipment.views.EquipmentStatsView
import dev.gvart.genesara.world.Biome
import dev.gvart.genesara.world.Climate
import dev.gvart.genesara.world.ItemCategory
import dev.gvart.genesara.world.Rarity
import dev.gvart.genesara.world.Terrain

/**
 * Kind of target the `inspect` tool resolves against. Explicit discriminator so a
 * numeric node id and a UUID agent id can never collide on the wire.
 */
enum class InspectTargetType { NODE, AGENT, ITEM, BUILDING }

/**
 * Variant-tagged response. Exactly one of [node] / [agent] / [item] / [error] is non-null,
 * keyed by [kind]. Kept flat (rather than a Jackson `JsonTypeInfo` polymorphic hierarchy)
 * to match the rest of this codebase's IO style.
 */
data class InspectResponse(
    val kind: String,
    val depth: String,
    val node: NodeInspectView? = null,
    val agent: AgentInspectView? = null,
    val item: ItemInspectView? = null,
    val building: BuildingInspectView? = null,
    val error: InspectError? = null,
)

/** Reasons a target couldn't be inspected. Distinct so agents can branch on them. */
data class InspectError(val code: String, val message: String) {
    companion object {
        const val NOT_FOUND = "NOT_FOUND"
        const val NOT_VISIBLE = "NOT_VISIBLE"
        const val NOT_IN_INVENTORY = "NOT_IN_INVENTORY"
        const val BAD_TARGET_ID = "BAD_TARGET_ID"
    }
}

data class NodeInspectView(
    val id: Long,
    val q: Int,
    val r: Int,
    val terrain: Terrain,
    val biome: Biome?,
    val climate: Climate?,
    /**
     * Visible item ids on this node. Always populated. Quantities only appear when the
     * agent is on the node itself OR has DETAILED+ Perception — adjacent-but-not-current
     * tiles at SHALLOW Perception only get item-id-level fog-of-war, matching `look_around`.
     */
    val resources: List<String>,
    /** Per-resource quantities. `null` at SHALLOW Perception when the node is not the agent's current tile. */
    val resourceQuantities: List<ResourceQuantityView>? = null,
    /**
     * EXPERT-only: PvP enabled flag and the biome's stamina-cost multiplier hint.
     * Lets a high-Perception agent assess "is this a green zone?" / "how punishing is
     * crossing this tile?" without actually moving onto it.
     */
    val expert: NodeExpertView? = null,
)

data class ResourceQuantityView(
    val itemId: String,
    val quantity: Int,
    val initialQuantity: Int,
)

data class NodeExpertView(
    val pvpEnabled: Boolean,
)

/**
 * Visibility model for inspecting another agent: same node only, banded vitals (no exact
 * numbers — that's reserved for Researcher-class scanning in Phase 4). Self-inspection
 * is allowed and follows the same banding for consistency; agents who want exact numbers
 * for themselves should call `get_status`.
 */
data class AgentInspectView(
    val id: String,
    val name: String,
    val race: String,
    val level: Int,
    /** Class id once assigned at level 10; null for unclassed agents. */
    val classId: String? = null,
    /** Banded HP: "low" / "mid" / "high" (or "dead" if 0). Always populated for same-node agents — matches `look_around`. */
    val hpBand: String? = null,
    /** Banded Stamina: same banding as HP. Always populated for same-node agents. */
    val staminaBand: String? = null,
    /**
     * Banded Mana: only set for psionic agents (`maxMana > 0`); null otherwise.
     * Non-psionic agents have no mana pool by canon.
     */
    val manaBand: String? = null,
    /** EXPERT-only list of visible status effect ids (Bleed, Burn, Stun, Poison...). */
    val activeEffects: List<String>? = null,
    /** DETAILED+: global authority (mechanics-reference §19). Surfaced so faction-aligned agents can read leadership weight. */
    val authority: Int? = null,
    /** DETAILED+: global fame. Below `BalanceLookup.fameWitnessProtectionThreshold` the agent has no PvP protection — visible context for any nearby attacker. */
    val fame: Int? = null,
)

/**
 * Item details for entries in the agent's own inventory. Phase-0 items are stackable
 * resources only — equipment with per-instance rarity / current durability / creator
 * signature lands with the equipment-slot slice. Catalog rarity ([rarity]) and the
 * catalog durability ceiling ([maxDurability]) ship now so the projection shape is
 * stable; for stackable resources rarity is always `COMMON` and `maxDurability` is null.
 */
data class ItemInspectView(
    val itemId: String,
    val displayName: String,
    val description: String,
    val category: ItemCategory,
    /** Quantity held in the agent's inventory. */
    val quantity: Int,
    /** DETAILED+: per-unit weight in grams + soft stack cap. */
    val weightPerUnit: Int? = null,
    val maxStack: Int? = null,
    /** DETAILED+: regen flag — true for organic gatherables, false for ores/stone/etc. */
    val regenerating: Boolean? = null,
    /**
     * DETAILED+: catalog rarity (COMMON / UNCOMMON / RARE / EPIC / LEGENDARY). Stackable
     * resources are always COMMON in v1; equipment items declare a higher floor here.
     * Per-instance equipment rolls override this on the equipment-instance row when
     * the equipment-slot slice ships.
     */
    val rarity: Rarity? = null,
    /**
     * DETAILED+: catalog durability ceiling for instances of this item. Null for
     * stackable resources (no durability concept). When equipment ships, this is
     * the max value a fresh instance starts at — current durability lives on the
     * per-instance row.
     */
    val maxDurability: Int? = null,
    /**
     * EXPERT-only: skill that harvesting this item trains, if any. Useful for an agent
     * deciding whether picking up an item also helps progression.
     */
    val harvestSkill: String? = null,
    /** DETAILED+: catalog projection for `EQUIPMENT` items. Null for stackable resources. */
    val equipmentStats: EquipmentStatsView? = null,
    /**
     * DETAILED+: per-instance state when the target id was an equipment instance UUID
     * (rolled rarity, live durability, creator signature). Null for stackable resources
     * AND when the agent inspected an equipment item by item id (catalog-only view).
     */
    val instanceState: InstanceStateView? = null,
    /**
     * DETAILED+: equipment-set membership ids. Empty list when the item is `EQUIPMENT`
     * but belongs to no set (positive "no set membership" signal). Null for stackable
     * resources where set membership is N/A.
     */
    val equipmentSets: List<String>? = null,
)

data class InstanceStateView(
    val rarity: Rarity,
    val durabilityCurrent: Int,
    val durabilityMax: Int,
    val creator: String? = null,
)

/**
 * Per-building inspect projection. For any building within sight, every per-instance field
 * (type, status, progress, hp band, exact hp, node id, builder agent id, `lastProgressTick`)
 * is always populated — matching what `look_around` already exposes for same-node buildings.
 * Catalog/recipe details (required skill, total / per-step materials, `builtAtTick`) are
 * visible to the owner regardless of Perception, and to EXPERT-Perception non-owners.
 * Chest contents are strictly owner-only (Phase 1 personal stash).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class BuildingInspectView(
    val instanceId: String,
    val type: String,
    val status: String,
    val progressSteps: Int,
    val totalSteps: Int,
    val hpBand: String,
    val nodeId: Long? = null,
    val builderAgentId: String? = null,
    val hpCurrent: Int? = null,
    val hpMax: Int? = null,
    val lastProgressTick: Long? = null,
    val builtAtTick: Long? = null,
    /**
     * Per-skill construction bars (single-bar T1, multi-bar T2). Each entry surfaces
     * the bar's required-skill, level gate, declared steps, and per-step material
     * cost. Total material cost per bar = `materialsPerStep × steps`.
     */
    val skillBars: List<BuildingSkillBarView>? = null,
    /**
     * Live per-bar progress for `UNDER_CONSTRUCTION` buildings. Populated only when
     * the inspector stands on the same node as the building, so co-located agents
     * can tell which bar to advance next on a multi-bar T2. Null for `ACTIVE`
     * buildings (all bars are full by definition) and for off-node inspectors.
     */
    val liveBars: List<BuildingBarProgressView>? = null,
    /** Owner-only: current chest contents. Null when not a chest, or the caller is not the builder. */
    val chestContents: List<BuildingMaterialView>? = null,
    /** GATE-only: true when the gate is OPEN (passable), false when CLOSED (blocks like a wall). Null for non-gate buildings and not-yet-ACTIVE gates. Mirrors `look_around`. */
    val isOpen: Boolean? = null,
)

data class BuildingSkillBarView(
    val skill: String,
    val requiredSkillLevel: Int,
    val steps: Int,
    val materialsPerStep: List<BuildingMaterialView>,
)

data class BuildingBarProgressView(
    val skill: String,
    val progressSteps: Int,
    val totalSteps: Int,
)

data class BuildingMaterialView(val itemId: String, val quantity: Int)
