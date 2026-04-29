package dev.gvart.genesara.world.internal.resources

import dev.gvart.genesara.world.Item
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.ItemLookup
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.NodeResources
import dev.gvart.genesara.world.NodeResourceView
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Component

/**
 * Redis-backed [NodeResourceStore] with a Postgres overlay for non-renewables.
 *
 * **Storage layout.** Per-node Hash, with packed field names per item:
 *
 * ```
 * Key:   world:nodeRes:{nodeId}
 * Hash:  {itemId}:q   →  current quantity
 *        {itemId}:i   →  initial quantity (immutable after seed)
 *        {itemId}:t   →  last regen at tick (advances by full intervals only)
 * ```
 *
 * Per-node max ~13 items × 3 fields = ~40 fields per Hash. Small enough to keep
 * `HGETALL` cheap and to fit the ziplist/listpack encoding for low memory overhead.
 *
 * **Persistence model.** Renewable items live in Redis only — a flush is a "world
 * reset" for them and the next paint repopulates at full initial quantity. Non-
 * renewable items (`Item.regenerating = false`: ORE / STONE / COAL / GEM / SALT) are
 * mirrored into Postgres via [NonRenewableResourceRegistry] so depletion survives
 * Redis flushes and server restarts. The next paint after a flush hydrates the
 * Redis cell for non-renewables from the persisted state instead of from the spawn
 * roll, so a mined-out deposit cannot be resurrected by re-painting the region.
 *
 * **Atomicity.**
 * - `decrement` uses a Lua script for atomic check-and-decrement (the WHERE q >= amount
 *   guard the Postgres impl had translates to a Lua conditional in Redis). For non-
 *   renewable items a Postgres upsert follows; the Redis mutation is the source of
 *   truth and the Postgres write is a durability mirror.
 * - `seed` uses `HSETNX` per field — first writer wins, repeated seeds are no-ops on
 *   already-populated cells. For non-renewables, a registry snapshot precedes the
 *   write so persisted (q, i) wins over the spawn roll.
 * - Lazy-regen writes are best-effort under contention. Two concurrent reads at the
 *   same tick compute the same regen and may both `HSET` the same value — idempotent
 *   in effect, with one redundant write at most. A future Lua-driven read could make
 *   this a CAS, but the redundancy cost in Redis is in microseconds.
 */
@Component
internal class RedisNodeResourceStore(
    private val redis: StringRedisTemplate,
    private val items: ItemLookup,
    private val nonRenewable: NonRenewableResourceRegistry,
) : NodeResourceStore {

    private val log = LoggerFactory.getLogger(javaClass)
    private val hash get() = redis.opsForHash<String, String>()

    override fun read(nodeId: NodeId, tick: Long): NodeResources {
        val raw = hash.entries(nodeKey(nodeId))
        if (raw.isEmpty()) return NodeResources.EMPTY

        val grouped = groupByItem(raw)
        val views = grouped.mapNotNull { (itemId, parts) ->
            val cell = parts.toCellOrNull(nodeId, itemId) ?: return@mapNotNull null
            val (newQuantity, newLastRegen) = applyRegen(cell, tick)
            if (newQuantity != cell.quantity || newLastRegen != cell.lastRegenAtTick) {
                writeBackRegen(nodeId, itemId, newQuantity, newLastRegen)
            }
            itemId to NodeResourceView(itemId, newQuantity, cell.initialQuantity)
        }.toMap()

        return NodeResources(views)
    }

    override fun availability(nodeId: NodeId, item: ItemId, tick: Long): NodeResourceCell? {
        val key = nodeKey(nodeId)
        val raw = hash.multiGet(key, listOf(qField(item), iField(item), tField(item)))
        val q = raw.getOrNull(0)?.toIntOrNull() ?: return null
        val i = raw.getOrNull(1)?.toIntOrNull() ?: return null
        val t = raw.getOrNull(2)?.toLongOrNull() ?: return null

        val cell = NodeResourceCellInternal(item, q, i, t)
        val (newQ, newT) = applyRegen(cell, tick)
        if (newQ != q || newT != t) {
            writeBackRegen(nodeId, item, newQ, newT)
        }
        return NodeResourceCell(nodeId, item, newQ, i)
    }

    override fun decrement(nodeId: NodeId, item: ItemId, amount: Int, tick: Long) {
        require(amount >= 0) { "decrement amount must be non-negative; got $amount" }
        if (amount == 0) return
        val result = redis.execute(
            DECREMENT_SCRIPT,
            listOf(nodeKey(nodeId)),
            qField(item),
            amount.toString(),
        )
        // Lua returns -1 when the cell is missing or quantity < amount. Note:
        // last_regen_at_tick is INTENTIONALLY not touched here — the read path advances
        // it by full intervals to preserve carryover; resetting it on every gather
        // would silently bleed effective regen.
        check(result != -1L) {
            "decrement failed for ($nodeId, $item, amount=$amount): cell missing or quantity below amount"
        }

        // Mirror non-renewable state to Postgres so depletion survives a Redis flush.
        // Renewables are deliberately skipped — their state is "ephemeral by design"
        // and a flush rolls them back to a fresh spawn at the next paint.
        val itemDef = items.byId(item) ?: return
        if (itemDef.regenerating) return
        val initialQuantity = hash.get(nodeKey(nodeId), iField(item))?.toIntOrNull()
        if (initialQuantity == null) {
            // The `:i` field is missing — only possible if `seed`'s three-step HSETNX
            // crashed between writing `:q` and `:i`. Bailing here is the safe choice:
            // persisting the post-decrement quantity as `initial_quantity` would
            // permanently lower the deposit's claimed initial size after the next
            // flush. The next `seed` call will repair the cell via the missing HSETNX.
            log.warn(
                "decrement of non-renewable item={} at node={} found no :i field; skipping registry write to avoid persisting a corrupted initialQuantity",
                item.value, nodeId.value,
            )
            return
        }
        nonRenewable.upsert(nodeId, item, quantity = result.toInt(), initialQuantity = initialQuantity)
    }

    override fun seed(rows: Collection<InitialResourceRow>, tick: Long) {
        if (rows.isEmpty()) return
        // Consult the persistent overlay BEFORE writing Redis cells: any non-renewable
        // item with a row in `non_renewable_resources` gets its (q, i) from there, not
        // from the spawn roll. This is what makes a flushed-and-repainted world keep its
        // mined-out ore deposits depleted.
        val byNode = rows.groupBy { it.nodeId }
        for ((nodeId, nodeRows) in byNode) {
            val nonRenewableIds = nodeRows.mapNotNull { row ->
                row.item.takeIf { items.byId(it)?.regenerating == false }
            }
            val persisted = if (nonRenewableIds.isEmpty()) {
                emptyMap()
            } else {
                nonRenewable.snapshot(nodeId, nonRenewableIds)
            }
            for (row in nodeRows) {
                val saved = persisted[row.item]
                val quantity = saved?.quantity ?: row.quantity
                val initialQuantity = saved?.initialQuantity ?: row.quantity
                val key = nodeKey(row.nodeId)
                // Atomic-per-field HSETNX. Repeated seeds are no-ops because the first
                // write populates `:q` (and `:i`/`:t`); subsequent calls find them
                // present and skip. Partial-write recovery: if a process crashes between
                // the three HSETNX calls a follow-up seed will fill the missing fields
                // and the cell becomes complete.
                hash.putIfAbsent(key, qField(row.item), quantity.toString())
                hash.putIfAbsent(key, iField(row.item), initialQuantity.toString())
                hash.putIfAbsent(key, tField(row.item), tick.toString())
            }
        }
    }

    // ─────────────────────────── regen math ────────────────────────────

    /**
     * Compute regen for one cell. Returns `(newQuantity, newLastRegenAtTick)`. Mirrors
     * the Postgres impl's logic: `last_regen_at_tick` advances by `events × interval`,
     * not to `tick`, so partial-window remainder carries forward across reads.
     */
    private fun applyRegen(cell: NodeResourceCellInternal, tick: Long): Pair<Int, Long> {
        val item = items.byId(cell.item) ?: return cell.quantity to cell.lastRegenAtTick
        return applyRegen(cell.quantity, cell.initialQuantity, cell.lastRegenAtTick, tick, item)
    }

    private fun applyRegen(
        quantity: Int,
        initialQuantity: Int,
        lastRegenAtTick: Long,
        tick: Long,
        item: Item,
    ): Pair<Int, Long> {
        if (!item.regenerating) return quantity to lastRegenAtTick
        if (item.regenIntervalTicks <= 0 || item.regenAmount <= 0) return quantity to lastRegenAtTick
        if (quantity >= initialQuantity) return quantity to lastRegenAtTick
        if (tick <= lastRegenAtTick) return quantity to lastRegenAtTick

        val elapsed = tick - lastRegenAtTick
        val events = (elapsed / item.regenIntervalTicks).toInt()
        if (events <= 0) return quantity to lastRegenAtTick
        val gain = events.toLong() * item.regenAmount.toLong()
        val newQuantity = (quantity + gain.coerceAtMost((initialQuantity - quantity).toLong())).toInt()
        val newLastRegen = lastRegenAtTick + events.toLong() * item.regenIntervalTicks.toLong()
        return newQuantity to newLastRegen
    }

    private fun writeBackRegen(nodeId: NodeId, item: ItemId, newQuantity: Int, newLastRegenAtTick: Long) {
        val key = nodeKey(nodeId)
        hash.put(key, qField(item), newQuantity.toString())
        hash.put(key, tField(item), newLastRegenAtTick.toString())
    }

    // ─────────────────────────── helpers ────────────────────────────

    private fun nodeKey(nodeId: NodeId) = "world:nodeRes:${nodeId.value}"
    private fun qField(item: ItemId) = "${item.value}:q"
    private fun iField(item: ItemId) = "${item.value}:i"
    private fun tField(item: ItemId) = "${item.value}:t"

    /** Group HGETALL results by item id (the prefix before the last `:`). */
    private fun groupByItem(raw: Map<String, String>): Map<ItemId, ItemFields> {
        val grouped = mutableMapOf<ItemId, ItemFields>()
        for ((field, value) in raw) {
            val sep = field.lastIndexOf(':')
            if (sep <= 0 || sep >= field.length - 1) continue
            val itemId = ItemId(field.substring(0, sep))
            val sub = field.substring(sep + 1)
            val bucket = grouped.getOrPut(itemId) { ItemFields() }
            when (sub) {
                "q" -> bucket.q = value
                "i" -> bucket.i = value
                "t" -> bucket.t = value
            }
        }
        return grouped
    }

    private class ItemFields(var q: String? = null, var i: String? = null, var t: String? = null) {
        fun toCellOrNull(nodeId: NodeId, item: ItemId): NodeResourceCellInternal? {
            val q = q?.toIntOrNull() ?: return null
            val i = i?.toIntOrNull() ?: return null
            val t = t?.toLongOrNull() ?: return null
            return NodeResourceCellInternal(item, q, i, t)
        }
    }

    /** In-package mirror of [NodeResourceCell] that keeps `lastRegenAtTick` for regen math. */
    private data class NodeResourceCellInternal(
        val item: ItemId,
        val quantity: Int,
        val initialQuantity: Int,
        val lastRegenAtTick: Long,
    )

    private companion object {
        /**
         * Atomic check-and-decrement. Returns the new quantity, or `-1` if the cell is
         * missing or holds less than the requested amount. Lua scripts run atomically in
         * Redis, so a concurrent gather can't see a stale read.
         *
         *   KEYS[1] = node hash key
         *   ARGV[1] = field name (`{itemId}:q`)
         *   ARGV[2] = amount to subtract
         */
        private val DECREMENT_SCRIPT = DefaultRedisScript<Long>(
            """
            local q = tonumber(redis.call('HGET', KEYS[1], ARGV[1]))
            if q == nil then return -1 end
            local amt = tonumber(ARGV[2])
            if q < amt then return -1 end
            return redis.call('HINCRBY', KEYS[1], ARGV[1], -amt)
            """.trimIndent(),
            Long::class.java,
        )
    }
}
