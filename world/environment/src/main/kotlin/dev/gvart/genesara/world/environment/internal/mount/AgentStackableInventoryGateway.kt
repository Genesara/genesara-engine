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
    fun decrement(agent: AgentId, item: ItemId, quantity: Int)
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
    override fun decrement(agent: AgentId, item: ItemId, quantity: Int) {
        require(quantity > 0) { "decrement quantity must be positive, was $quantity" }
        val deleted = dsl.deleteFrom(AGENT_INVENTORY)
            .where(AGENT_INVENTORY.AGENT_ID.eq(agent.id))
            .and(AGENT_INVENTORY.ITEM_ID.eq(item.value))
            .and(AGENT_INVENTORY.QUANTITY.eq(quantity))
            .execute()
        if (deleted > 0) return
        dsl.update(AGENT_INVENTORY)
            .set(AGENT_INVENTORY.QUANTITY, AGENT_INVENTORY.QUANTITY.minus(quantity))
            .where(AGENT_INVENTORY.AGENT_ID.eq(agent.id))
            .and(AGENT_INVENTORY.ITEM_ID.eq(item.value))
            .execute()
    }
}
