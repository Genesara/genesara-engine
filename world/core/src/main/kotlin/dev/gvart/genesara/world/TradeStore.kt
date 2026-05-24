package dev.gvart.genesara.world

import dev.gvart.genesara.player.AgentId
import java.util.UUID

/**
 * Persistent store for trade offers between agents. Rows are inserted at offer time
 * (PENDING) and updated to a terminal status (ACCEPTED / REJECTED) at respond time.
 *
 * Concurrency contract: [findPendingForUpdate] takes a row-level lock on the trade
 * row inside the calling transaction. Two concurrent respond reducers targeting
 * the same trade serialize on this lock; the loser sees a null and the reducer
 * surfaces [WorldRejection.TradeNotPending].
 */
interface TradeStore {
    fun create(offer: TradeOffer)

    /** Read the trade row regardless of status. Used by the respond reducer to tell
     *  [WorldRejection.TradeNotFound] apart from [WorldRejection.TradeNotPending]. */
    fun find(tradeId: UUID): TradeOffer?

    /** Take a row-level lock on the trade row when its status is PENDING. Two concurrent
     *  responders racing on the same trade serialize on this lock; the loser sees null. */
    fun findPendingForUpdate(tradeId: UUID): TradeOffer?

    fun markResolved(tradeId: UUID, status: TradeStatus, resolvedAtTick: Long): Boolean

    companion object {
        /** Stub for tests that never queue a trade command. */
        val NoOp: TradeStore = object : TradeStore {
            override fun create(offer: TradeOffer): Unit = error("NoOp TradeStore.create")
            override fun find(tradeId: UUID): TradeOffer? = null
            override fun findPendingForUpdate(tradeId: UUID): TradeOffer? = null
            override fun markResolved(tradeId: UUID, status: TradeStatus, resolvedAtTick: Long): Boolean = false
        }
    }
}

data class TradeOffer(
    val tradeId: UUID,
    val offerer: AgentId,
    val recipient: AgentId,
    val offered: Map<ItemId, Int>,
    val requested: Map<ItemId, Int>,
    val status: TradeStatus,
    val openedAtTick: Long,
    val resolvedAtTick: Long?,
    val offeredInstances: Set<UUID> = emptySet(),
    val requestedInstances: Set<UUID> = emptySet(),
)
