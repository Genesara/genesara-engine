package dev.gvart.genesara.api.internal.mcp.tools.inventory

data class GetInventoryResponse(
    val entries: List<InventoryEntryView>,
)

/**
 * One stackable inventory entry. [rarity] reflects the catalog default for the
 * item (always `COMMON` in v1 — every item in the resource catalog is
 * COMMON-rarity). Per-instance equipment is reported separately when the
 * equipment-slot slice ships and is not included here.
 */
data class InventoryEntryView(
    val itemId: String,
    val quantity: Int,
    val rarity: String,
)
