package dev.gvart.genesara.api.internal.mcp.tools.inspect

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription

@JsonClassDescription(
    "Look at a single target (node, agent, item, or building) in detail. " +
        "Visibility-gated: nodes and buildings must be within sight, agents must be in the same node, " +
        "items must be in the agent's own inventory. The depth of the response scales with " +
        "the calling agent's Perception attribute (3 tiers).",
)
data class InspectRequest(
    @field:JsonPropertyDescription(
        "Kind of target to inspect. Must be one of: \"node\", \"agent\", \"item\", \"building\". " +
            "Explicit so a numeric node id and a UUID agent id can never collide.",
    )
    val targetType: String?,
    @field:JsonPropertyDescription(
        "Target id. For node this is the numeric BIGINT id; for agent and building this is the UUID; " +
            "for item this is the ItemId string.",
    )
    val targetId: String?,
)

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
        const val BAD_TARGET_TYPE = "BAD_TARGET_TYPE"
        const val BAD_TARGET_ID = "BAD_TARGET_ID"
    }
}

data class NodeInspectView(
    val id: Long,
    val q: Int,
    val r: Int,
    val terrain: String,
    val biome: String?,
    val climate: String?,
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
    /** Class id once assigned at level 10; null for unclassed agents. DETAILED+. */
    val classId: String? = null,
    /** Banded HP: "low" / "mid" / "high" (or "dead" if 0). DETAILED+. */
    val hpBand: String? = null,
    /** Banded Stamina: same banding as HP. DETAILED+. */
    val staminaBand: String? = null,
    /**
     * Banded Mana: only set for psionic agents (`maxMana > 0`); null otherwise.
     * Non-psionic agents have no mana pool by canon. DETAILED+.
     */
    val manaBand: String? = null,
    /** EXPERT-only list of visible status effect ids (Bleed, Burn, Stun, Poison...). */
    val activeEffects: List<String>? = null,
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
    val category: String,
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
    val rarity: String? = null,
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
)

/**
 * Per-building inspect projection. Type, status, hp band, and progress (`progressSteps` /
 * `totalSteps`) are always present — agents need progress to know whether to keep building.
 * DETAILED+ adds the precise hp values, the node id, the builder agent id, and a tick-based
 * `lastProgressTick`. EXPERT adds the per-step materials breakdown from the catalog and the
 * required skill / level. Chest contents are EXPERT-only AND owner-only (Phase 1 personal
 * stash).
 */
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
    val requiredSkill: String? = null,
    val requiredSkillLevel: Int? = null,
    val totalMaterials: List<BuildingMaterialView>? = null,
    val stepMaterials: List<List<BuildingMaterialView>>? = null,
    /** EXPERT + owner-only: current chest contents. Null when not a chest, not owner, or below EXPERT. */
    val chestContents: List<BuildingMaterialView>? = null,
)

data class BuildingMaterialView(val itemId: String, val quantity: Int)
