package dev.gvart.genesara.world.internal.resources

import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.internal.jooq.tables.references.NON_RENEWABLE_RESOURCES
import org.jooq.DSLContext
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Postgres-backed overlay for non-renewable resources (ORE, STONE, COAL, GEM, SALT).
 * Mirrors the Redis cell for items where `Item.regenerating = false`, so depletion
 * survives a Redis flush or server restart — the user-facing invariant the slice 5
 * design requires.
 *
 * Renewables (WOOD, BERRY, HERB, MUSHROOM, FISH, CLAY, SAND, PEAT) are NOT recorded
 * here: their state lives only in Redis. A Redis flush effectively "resets" them
 * because the next paint reseeds them at their full initial roll, equivalent to a
 * world-reset season transition.
 *
 * Update path: every successful `decrement` of a non-renewable item upserts a row
 * here. Read path: `RedisNodeResourceStore.seed` consults this registry before
 * writing the Redis cell, so a re-paint after a flush hydrates the cell from the
 * persisted state instead of resurrecting a mined-out deposit.
 */
internal interface NonRenewableResourceRegistry {

    /**
     * Persisted state per item for [nodeId], filtered to [items]. Empty map if no
     * non-renewable rows have ever been written for these (node, item) pairs.
     */
    fun snapshot(nodeId: NodeId, items: Collection<ItemId>): Map<ItemId, NonRenewableState>

    /**
     * Upsert the live (quantity, initialQuantity) for a single (node, item) pair.
     * Called from `decrement` for non-renewable items only.
     */
    fun upsert(nodeId: NodeId, item: ItemId, quantity: Int, initialQuantity: Int)
}

internal data class NonRenewableState(val quantity: Int, val initialQuantity: Int)

@Component
internal class JooqNonRenewableResourceRegistry(
    private val dsl: DSLContext,
) : NonRenewableResourceRegistry {

    @Transactional(readOnly = true)
    override fun snapshot(nodeId: NodeId, items: Collection<ItemId>): Map<ItemId, NonRenewableState> {
        if (items.isEmpty()) return emptyMap()
        return dsl.select(
            NON_RENEWABLE_RESOURCES.ITEM_ID,
            NON_RENEWABLE_RESOURCES.QUANTITY,
            NON_RENEWABLE_RESOURCES.INITIAL_QUANTITY,
        )
            .from(NON_RENEWABLE_RESOURCES)
            .where(NON_RENEWABLE_RESOURCES.NODE_ID.eq(nodeId.value))
            .and(NON_RENEWABLE_RESOURCES.ITEM_ID.`in`(items.map { it.value }))
            .fetch()
            .associate { row ->
                ItemId(row[NON_RENEWABLE_RESOURCES.ITEM_ID]!!) to NonRenewableState(
                    quantity = row[NON_RENEWABLE_RESOURCES.QUANTITY]!!,
                    initialQuantity = row[NON_RENEWABLE_RESOURCES.INITIAL_QUANTITY]!!,
                )
            }
    }

    @Transactional
    override fun upsert(nodeId: NodeId, item: ItemId, quantity: Int, initialQuantity: Int) {
        dsl.insertInto(NON_RENEWABLE_RESOURCES)
            .set(NON_RENEWABLE_RESOURCES.NODE_ID, nodeId.value)
            .set(NON_RENEWABLE_RESOURCES.ITEM_ID, item.value)
            .set(NON_RENEWABLE_RESOURCES.QUANTITY, quantity)
            .set(NON_RENEWABLE_RESOURCES.INITIAL_QUANTITY, initialQuantity)
            .onConflict(NON_RENEWABLE_RESOURCES.NODE_ID, NON_RENEWABLE_RESOURCES.ITEM_ID)
            .doUpdate()
            .set(NON_RENEWABLE_RESOURCES.QUANTITY, quantity)
            // initial_quantity is set on first insert and never updated thereafter; the
            // ON CONFLICT clause leaves it alone (no .set for it here).
            .execute()
    }
}
