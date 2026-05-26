package dev.gvart.genesara.world.internal.inventory

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.AdjustResult
import dev.gvart.genesara.world.AgentInventoryAdminStore
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.internal.jooq.tables.references.AGENT_INVENTORY
import org.jooq.DSLContext
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
internal class JooqAgentInventoryAdminStore(
    private val dsl: DSLContext,
) : AgentInventoryAdminStore {

    @Transactional
    override fun adjust(agentId: AgentId, itemId: ItemId, delta: Int): AdjustResult {
        val current = dsl.select(AGENT_INVENTORY.QUANTITY)
            .from(AGENT_INVENTORY)
            .where(AGENT_INVENTORY.AGENT_ID.eq(agentId.id))
            .and(AGENT_INVENTORY.ITEM_ID.eq(itemId.value))
            .fetchOne(AGENT_INVENTORY.QUANTITY)
            ?: 0
        val next = current + delta
        if (next < 0) return AdjustResult.Insufficient(have = current, asked = -delta)
        when {
            next == 0 && current > 0 -> {
                dsl.deleteFrom(AGENT_INVENTORY)
                    .where(AGENT_INVENTORY.AGENT_ID.eq(agentId.id))
                    .and(AGENT_INVENTORY.ITEM_ID.eq(itemId.value))
                    .execute()
            }
            next > 0 -> {
                dsl.insertInto(AGENT_INVENTORY)
                    .set(AGENT_INVENTORY.AGENT_ID, agentId.id)
                    .set(AGENT_INVENTORY.ITEM_ID, itemId.value)
                    .set(AGENT_INVENTORY.QUANTITY, next)
                    .onConflict(AGENT_INVENTORY.AGENT_ID, AGENT_INVENTORY.ITEM_ID)
                    .doUpdate()
                    .set(AGENT_INVENTORY.QUANTITY, next)
                    .execute()
            }
        }
        return AdjustResult.Adjusted(quantityAfter = next)
    }

    @Transactional
    override fun removeAll(agentId: AgentId, itemId: ItemId): Int {
        val prior = dsl.select(AGENT_INVENTORY.QUANTITY)
            .from(AGENT_INVENTORY)
            .where(AGENT_INVENTORY.AGENT_ID.eq(agentId.id))
            .and(AGENT_INVENTORY.ITEM_ID.eq(itemId.value))
            .fetchOne(AGENT_INVENTORY.QUANTITY)
            ?: 0
        if (prior > 0) {
            dsl.deleteFrom(AGENT_INVENTORY)
                .where(AGENT_INVENTORY.AGENT_ID.eq(agentId.id))
                .and(AGENT_INVENTORY.ITEM_ID.eq(itemId.value))
                .execute()
        }
        return prior
    }
}
