package dev.gvart.genesara.world.environment.internal.mount

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.internal.jooq.tables.references.AGENT_INVENTORY
import org.jooq.DSLContext
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Narrow direct-write port to `agent_inventory`. Carved out of [MountCargoServiceImpl]
 * because the cargo service is sync (no `EnvironmentCommand`) and the reducer-driven
 * `WorldStateRepository` save path operates on full-agent snapshots — too coarse for
 * cargo moves. Production wiring uses [JooqAgentStackableInventoryGateway]; unit tests
 * substitute an in-memory fake.
 */
internal interface AgentStackableInventoryGateway {
    fun quantityOf(agent: AgentId, item: ItemId): Int
    fun increment(agent: AgentId, item: ItemId, quantity: Int)

    /**
     * Decrement (agent, item) by [quantity]. Returns true when the write
     * succeeded (the row had at least [quantity] units); false when the
     * row was missing or had less than [quantity]. Callers MUST pre-check
     * via [quantityOf] before calling — this method's WHERE guard is the
     * race fence against concurrent decrements, NOT a substitute for the
     * caller-side existence check.
     */
    fun decrement(agent: AgentId, item: ItemId, quantity: Int): Boolean
}

@Component
internal class JooqAgentStackableInventoryGateway(
    private val dsl: DSLContext,
) : AgentStackableInventoryGateway {

    @Transactional(readOnly = true)
    override fun quantityOf(agent: AgentId, item: ItemId): Int =
        dsl.select(AGENT_INVENTORY.QUANTITY)
            .from(AGENT_INVENTORY)
            .where(AGENT_INVENTORY.AGENT_ID.eq(agent.id))
            .and(AGENT_INVENTORY.ITEM_ID.eq(item.value))
            .fetchOne(AGENT_INVENTORY.QUANTITY) ?: 0

    @Transactional
    override fun increment(agent: AgentId, item: ItemId, quantity: Int) {
        require(quantity > 0) { "increment quantity must be positive, was $quantity" }
        dsl.insertInto(AGENT_INVENTORY)
            .set(AGENT_INVENTORY.AGENT_ID, agent.id)
            .set(AGENT_INVENTORY.ITEM_ID, item.value)
            .set(AGENT_INVENTORY.QUANTITY, quantity)
            .onConflict(AGENT_INVENTORY.AGENT_ID, AGENT_INVENTORY.ITEM_ID)
            .doUpdate()
            .set(AGENT_INVENTORY.QUANTITY, AGENT_INVENTORY.QUANTITY.plus(quantity))
            .execute()
    }

    @Transactional
    override fun decrement(agent: AgentId, item: ItemId, quantity: Int): Boolean {
        require(quantity > 0) { "decrement quantity must be positive, was $quantity" }
        val deleted = dsl.deleteFrom(AGENT_INVENTORY)
            .where(AGENT_INVENTORY.AGENT_ID.eq(agent.id))
            .and(AGENT_INVENTORY.ITEM_ID.eq(item.value))
            .and(AGENT_INVENTORY.QUANTITY.eq(quantity))
            .execute()
        if (deleted > 0) return true
        // The WHERE-clause guard fences the UPDATE against a concurrent
        // decrement that already drove the row below `quantity`. The
        // CHECK (quantity > 0) is still the backstop if the guard slips.
        val updated = dsl.update(AGENT_INVENTORY)
            .set(AGENT_INVENTORY.QUANTITY, AGENT_INVENTORY.QUANTITY.minus(quantity))
            .where(AGENT_INVENTORY.AGENT_ID.eq(agent.id))
            .and(AGENT_INVENTORY.ITEM_ID.eq(item.value))
            .and(AGENT_INVENTORY.QUANTITY.ge(quantity))
            .execute()
        return updated > 0
    }
}
