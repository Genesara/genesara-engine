package dev.gvart.genesara.world.internal.trade

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.TradeOffer
import dev.gvart.genesara.world.TradeStatus
import dev.gvart.genesara.world.TradeStore
import dev.gvart.genesara.world.internal.jooq.tables.references.TRADE_OFFERS
import org.jooq.DSLContext
import org.jooq.JSON
import org.jooq.Record
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import java.util.UUID

@Component
internal class JooqTradeStore(
    private val dsl: DSLContext,
    private val mapper: ObjectMapper,
) : TradeStore {

    @Transactional
    override fun create(offer: TradeOffer) {
        dsl.insertInto(TRADE_OFFERS)
            .set(TRADE_OFFERS.TRADE_ID, offer.tradeId)
            .set(TRADE_OFFERS.OFFERER_ID, offer.offerer.id)
            .set(TRADE_OFFERS.RECIPIENT_ID, offer.recipient.id)
            .set(TRADE_OFFERS.OFFERED, encode(offer.offered))
            .set(TRADE_OFFERS.REQUESTED, encode(offer.requested))
            .set(TRADE_OFFERS.STATUS, offer.status.name)
            .set(TRADE_OFFERS.OPENED_AT_TICK, offer.openedAtTick)
            .set(TRADE_OFFERS.RESOLVED_AT_TICK, offer.resolvedAtTick)
            .execute()
    }

    @Transactional(readOnly = true)
    override fun find(tradeId: UUID): TradeOffer? =
        dsl.selectFrom(TRADE_OFFERS)
            .where(TRADE_OFFERS.TRADE_ID.eq(tradeId))
            .fetchOne()
            ?.let(::toDomain)

    @Transactional
    override fun findPendingForUpdate(tradeId: UUID): TradeOffer? =
        dsl.selectFrom(TRADE_OFFERS)
            .where(TRADE_OFFERS.TRADE_ID.eq(tradeId))
            .and(TRADE_OFFERS.STATUS.eq(TradeStatus.PENDING.name))
            .forUpdate()
            .fetchOne()
            ?.let(::toDomain)

    @Transactional
    override fun markResolved(tradeId: UUID, status: TradeStatus, resolvedAtTick: Long): Boolean {
        require(status != TradeStatus.PENDING) { "resolved status cannot be PENDING" }
        val updated = dsl.update(TRADE_OFFERS)
            .set(TRADE_OFFERS.STATUS, status.name)
            .set(TRADE_OFFERS.RESOLVED_AT_TICK, resolvedAtTick)
            .where(TRADE_OFFERS.TRADE_ID.eq(tradeId))
            .and(TRADE_OFFERS.STATUS.eq(TradeStatus.PENDING.name))
            .execute()
        return updated > 0
    }

    private fun encode(items: Map<ItemId, Int>): JSON =
        JSON.valueOf(mapper.writeValueAsString(items.mapKeys { it.key.value }))

    private fun decode(json: JSON): Map<ItemId, Int> =
        mapper.readValue(json.data(), STACK_MAP_TYPE).mapKeys { ItemId(it.key) }

    private fun toDomain(record: Record): TradeOffer = TradeOffer(
        tradeId = record[TRADE_OFFERS.TRADE_ID]!!,
        offerer = AgentId(record[TRADE_OFFERS.OFFERER_ID]!!),
        recipient = AgentId(record[TRADE_OFFERS.RECIPIENT_ID]!!),
        offered = decode(record[TRADE_OFFERS.OFFERED]!!),
        requested = decode(record[TRADE_OFFERS.REQUESTED]!!),
        status = TradeStatus.valueOf(record[TRADE_OFFERS.STATUS]!!),
        openedAtTick = record[TRADE_OFFERS.OPENED_AT_TICK]!!,
        resolvedAtTick = record[TRADE_OFFERS.RESOLVED_AT_TICK],
    )

    private companion object {
        private val STACK_MAP_TYPE = object : TypeReference<Map<String, Int>>() {}
    }
}
