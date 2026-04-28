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
)

enum class ItemCategory {
    RESOURCE,
    // Future: TOOL, WEAPON, ARMOR — handled in the equipment slice.
}
