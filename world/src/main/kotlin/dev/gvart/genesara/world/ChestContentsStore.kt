package dev.gvart.genesara.world

import java.util.UUID

/**
 * Persistent stash backed by `world.building_chest_inventory`. The persistent
 * analog of the in-memory `AgentInventory` add/remove shape.
 *
 * Every chest variant (STORAGE_CHEST in Phase 1, future CLAN_STORAGE_CHEST in
 * Phase 3, banker buildings, drop-boxes) writes rows keyed on its own
 * `building_id`. Access semantics — who can deposit, who can withdraw — live
 * in the per-variant reducer, NOT in this store. The store is a pure
 * key-value bag of stack quantities.
 */
interface ChestContentsStore {

    /** Quantity of [item] in the chest, or 0 when no row exists. */
    fun quantityOf(buildingId: UUID, item: ItemId): Int

    /**
     * Full snapshot of the chest's contents. Empty for a chest that has never
     * received a deposit. Returned map is read-only.
     */
    fun contentsOf(buildingId: UUID): Map<ItemId, Int>

    /**
     * Add [quantity] of [item] to the chest. Inserts a new row when none
     * exists, otherwise increments the existing quantity. [quantity] must be
     * positive — non-positive add is a programmer error and throws.
     */
    fun add(buildingId: UUID, item: ItemId, quantity: Int)

    /**
     * Remove [quantity] of [item] from the chest. Returns `true` if the
     * removal succeeded, `false` if the chest holds fewer than [quantity] of
     * [item] (no rows are mutated in that case). Removing the last unit
     * deletes the row so an empty chest has zero rows in the table.
     */
    fun remove(buildingId: UUID, item: ItemId, quantity: Int): Boolean
}
