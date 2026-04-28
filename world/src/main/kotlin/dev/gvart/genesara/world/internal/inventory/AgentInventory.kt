package dev.gvart.genesara.world.internal.inventory

import dev.gvart.genesara.world.ItemId

/**
 * In-memory representation of an agent's stackable inventory. Wraps an immutable
 * `Map<ItemId, Int>` so reducer mutations are pure copies.
 *
 * Slice 2 ships only stackable resources; per-instance equipment with rarity /
 * durability gets a different shape and lands with the equipment slice.
 */
internal data class AgentInventory(
    val stacks: Map<ItemId, Int> = emptyMap(),
) {

    fun add(item: ItemId, quantity: Int): AgentInventory {
        require(quantity > 0) { "quantity to add must be positive, was $quantity" }
        val current = stacks[item] ?: 0
        return AgentInventory(stacks + (item to current + quantity))
    }

    fun quantityOf(item: ItemId): Int = stacks[item] ?: 0

    companion object {
        val EMPTY: AgentInventory = AgentInventory()
    }
}
