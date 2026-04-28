package dev.gvart.genesara.world

/**
 * Public, read-only projection of an agent's inventory. Returned by
 * [WorldQueryGateway.inventoryOf] for sync-read tools (e.g. `get_inventory`)
 * so the `:api` module never needs to touch the internal `AgentInventory`
 * representation.
 */
data class InventoryView(
    val entries: List<InventoryEntry>,
)

data class InventoryEntry(
    val itemId: ItemId,
    val quantity: Int,
)
