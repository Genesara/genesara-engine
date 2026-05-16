package dev.gvart.genesara.world

/**
 * Per-mob drop rule for a single resource. Built by [LootTableCatalog] from
 * the resource-keyed `loot-tables.yaml` into a mob-keyed inverted index for
 * O(1) per-kill lookup.
 *
 * Stackable items use [quantityMin]/[quantityMax]; equipment items roll
 * quantity of exactly 1 (the catalog rejects ranges >1 for equipment).
 */
data class LootEntry(
    val item: ItemId,
    val dropChance: Double,
    val quantityMin: Int,
    val quantityMax: Int,
)

interface LootTableCatalog {
    /** Every drop entry that fires on a kill of [mob]. Empty for unmodelled mobs. */
    fun byMob(mob: NpcType): List<LootEntry>
}
