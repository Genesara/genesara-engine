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
 * **Storage layout.** Per-node Hash, packed item fields:
 *
 * ```
 * Key:   world:nodeRes:{nodeId}
 * Hash:  {itemId}:q   →  current quantity
 *        {itemId}:i   →  initial quantity (immutable after seed)
 *        {itemId}:t   →  last regen at tick (advances by full intervals only)
 * ```
 *
 * Per-node max ~13 items × 3 fields = ~40 fields. Small enough to keep `HGETALL` cheap
 * and to fit Redis's listpack encoding for low memory overhead.
 *
 * **Persistence.** Renewables live in Redis only — a flush resets them, the next paint
 * repopulates at full initial quantity. Non-renewables (`Item.regenerating = false`:
 * ORE / STONE / COAL / GEM / SALT) are mirrored to Postgres via
 * [NonRenewableResourceRegistry] so depletion survives flushes; the next paint hydrates
 * the Redis cell from the persisted state instead of the spawn roll, so a mined-out
 * deposit cannot be resurrected by re-painting.
 *
 * **Atomicity.** [decrement] is a Lua check-and-decrement — atomic in Redis, with a
 * Postgres mirror for non-renewables (Redis is the source of truth, Postgres is the
 * durability mirror). [seed] uses per-field `HSETNX` — first writer wins; partial-write
 * recovery is automatic on the next seed call. Lazy-regen writes are best-effort under
 * contention: two concurrent reads at the same tick produce the same value, so
 * redundant `HSET` is idempotent.
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
        val newQuantity = atomicDecrement(nodeId, item, amount)
        mirrorNonRenewableToPostgres(nodeId, item, newQuantity)
    }

    /**
     * Atomic check-and-decrement via Lua. `last_regen_at_tick` is intentionally not
     * touched — the read path advances it by full intervals to preserve carryover, and
     * resetting it on every gather would silently bleed effective regen.
     */
    private fun atomicDecrement(nodeId: NodeId, item: ItemId, amount: Int): Int {
        val result = redis.execute(
            DECREMENT_SCRIPT,
            listOf(nodeKey(nodeId)),
            qField(item),
            amount.toString(),
        )
        check(result != -1L) {
            "decrement failed for ($nodeId, $item, amount=$amount): cell missing or quantity below amount"
        }
        return result.toInt()
    }

    private fun mirrorNonRenewableToPostgres(nodeId: NodeId, item: ItemId, newQuantity: Int) {
        val itemDef = items.byId(item) ?: return
        if (itemDef.regenerating) return
        val initialQuantity = hash.get(nodeKey(nodeId), iField(item))?.toIntOrNull()
        if (initialQuantity == null) {
            log.warn(
                "decrement of non-renewable item={} at node={} found no :i field; skipping registry write to avoid persisting a corrupted initialQuantity",
                item.value, nodeId.value,
            )
            return
        }
        nonRenewable.upsert(nodeId, item, quantity = newQuantity, initialQuantity = initialQuantity)
    }

    /**
     * Persisted state wins over spawn rolls: a non-renewable with a row in
     * `non_renewable_resources` overrides the seed's (q, i). Per-field `HSETNX` makes
     * repeated seeds no-ops and auto-repairs partial writes from prior crashes.
     */
    override fun seed(rows: Collection<InitialResourceRow>, tick: Long) {
        if (rows.isEmpty()) return
        for ((nodeId, nodeRows) in rows.groupBy { it.nodeId }) {
            val persisted = persistedNonRenewables(nodeId, nodeRows)
            for (row in nodeRows) {
                val saved = persisted[row.item]
                writeSeedFields(
                    nodeId = row.nodeId,
                    item = row.item,
                    quantity = saved?.quantity ?: row.quantity,
                    initialQuantity = saved?.initialQuantity ?: row.quantity,
                    tick = tick,
                )
            }
        }
    }

    private fun persistedNonRenewables(
        nodeId: NodeId,
        nodeRows: List<InitialResourceRow>,
    ): Map<ItemId, NonRenewableState> {
        val ids = nodeRows.mapNotNull { row ->
            row.item.takeIf { items.byId(it)?.regenerating == false }
        }
        return if (ids.isEmpty()) emptyMap() else nonRenewable.snapshot(nodeId, ids)
    }

    private fun writeSeedFields(nodeId: NodeId, item: ItemId, quantity: Int, initialQuantity: Int, tick: Long) {
        val key = nodeKey(nodeId)
        hash.putIfAbsent(key, qField(item), quantity.toString())
        hash.putIfAbsent(key, iField(item), initialQuantity.toString())
        hash.putIfAbsent(key, tField(item), tick.toString())
    }

    private fun applyRegen(cell: NodeResourceCellInternal, tick: Long): Pair<Int, Long> {
        val item = items.byId(cell.item) ?: return cell.quantity to cell.lastRegenAtTick
        return applyRegen(cell.quantity, cell.initialQuantity, cell.lastRegenAtTick, tick, item)
    }

    /**
     * Advances `last_regen_at_tick` by `events × interval` — never to `tick` — so
     * partial-window remainder carries forward across reads.
     */
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

    private fun nodeKey(nodeId: NodeId) = "world:nodeRes:${nodeId.value}"
    private fun qField(item: ItemId) = "${item.value}:q"
    private fun iField(item: ItemId) = "${item.value}:i"
    private fun tField(item: ItemId) = "${item.value}:t"

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

    private data class NodeResourceCellInternal(
        val item: ItemId,
        val quantity: Int,
        val initialQuantity: Int,
        val lastRegenAtTick: Long,
    )

    private companion object {
        /**
         * Atomic check-and-decrement. Returns the new quantity, or `-1` if the cell is
         * missing or holds less than the requested amount.
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
