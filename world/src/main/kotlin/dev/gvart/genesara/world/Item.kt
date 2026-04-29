package dev.gvart.genesara.world

/**
 * An entry from the world's item catalog (`world-definition/items.yaml`).
 *
 * Slice 2 ships only [ItemCategory.RESOURCE] — stackable, no per-instance state.
 * Equipment with rarity / durability / per-instance attributes ships with the
 * equipment slice and uses a different storage shape (separate table).
 */
data class Item(
    val id: ItemId,
    val displayName: String,
    val description: String,
    val category: ItemCategory,
    /** Weight in grams per single unit. Used for the future Strength-bounded carry cap. */
    val weightPerUnit: Int,
    /** Soft cap on a single inventory stack; gather can't push a stack above this. */
    val maxStack: Int,
    /**
     * Effect applied when this item is consumed via the `consume` MCP tool. `null` for
     * non-consumables (raw resources used only as crafting inputs).
     */
    val consumable: ConsumableEffect? = null,
    /**
     * If true, depleted node deposits of this item slowly recover toward their initial
     * roll. If false, depletion is permanent (stone, ore, coal, gems, salt).
     *
     * Default true matches the more common case (organic gatherables).
     */
    val regenerating: Boolean = true,
    /**
     * Number of ticks between successive regen events. The lazy-regen logic in
     * [dev.gvart.genesara.world.internal.resources.NodeResourceStore] reads this when
     * computing how many "regen intervals" have elapsed since the last write.
     *
     * `0` disables regen even when [regenerating] is true (a cheap fast-path; tests
     * that don't care about regen can leave both fields at default).
     */
    val regenIntervalTicks: Int = 0,
    /** Quantity added per regen interval, capped at the per-node initial quantity. */
    val regenAmount: Int = 0,
    /**
     * Skill id (from `:player`'s catalog) that a `gather` of this item trains. Null
     * for non-gatherable items or for resources that aren't tied to a skill (none
     * today). Cross-validated against the skill catalog at startup.
     */
    val gatheringSkill: String? = null,
)

enum class ItemCategory {
    RESOURCE,
    // Future: TOOL, WEAPON, ARMOR — handled in the equipment slice.
}
