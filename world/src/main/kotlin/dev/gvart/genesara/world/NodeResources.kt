package dev.gvart.genesara.world

/**
 * Read-side projection of one node's live resource state. Returned by the world query
 * gateway for use in `look_around` and the dashboard.
 */
data class NodeResources(val entries: Map<ItemId, NodeResourceView>) {
    fun quantityOf(item: ItemId): Int = entries[item]?.quantity ?: 0

    companion object {
        val EMPTY = NodeResources(emptyMap())
    }
}

data class NodeResourceView(
    val itemId: ItemId,
    val quantity: Int,
    val initialQuantity: Int,
)
