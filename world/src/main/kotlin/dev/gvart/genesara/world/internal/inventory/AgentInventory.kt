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

    /**
     * Decrement [quantity] from the named stack. Removes the entry entirely when the
     * stack reaches zero so the persistence layer can drop the row (the DB CHECK
     * forbids zero-quantity rows anyway).
     *
     * Throws [IllegalArgumentException] when the agent doesn't have enough — the caller
     * must validate via [quantityOf] first; this is a value-level invariant, not an
     * agent-facing rejection.
     */
    fun remove(item: ItemId, quantity: Int): AgentInventory {
        require(quantity > 0) { "quantity to remove must be positive, was $quantity" }
        val current = stacks[item] ?: 0
        require(current >= quantity) {
            "agent does not have enough $item (has $current, asked for $quantity)"
        }
        val remaining = current - quantity
        return if (remaining == 0) AgentInventory(stacks - item)
        else AgentInventory(stacks + (item to remaining))
    }

    fun quantityOf(item: ItemId): Int = stacks[item] ?: 0

    companion object {
        val EMPTY: AgentInventory = AgentInventory()
    }
}
