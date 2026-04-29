package dev.gvart.genesara.world.internal.resources

import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.NodeResources

/**
 * Per-node resource availability. Backed by Redis (see [RedisNodeResourceStore]) per the
 * tech-stack rationale: high-frequency, small-payload, key-addressable lookups on a hot
 * read path (every `gather` and every `look_around` hits this). Resource state is
 * intentionally ephemeral — wipes are tolerable because the season-reset model assumes
 * periodic world resets, and a deterministic `ResourceSpawner` reproduces the initial
 * layout from world id alone.
 *
 * **Lazy regen on read.** Reads are non-trivial: for regenerating items, the store
 * computes how many `regen-interval-ticks` have elapsed since `last_regen_at_tick`,
 * tops the cell up by `regen-amount × elapsed` (capped at `initial_quantity`), and
 * writes back before returning. This trades a slightly heavier read path for a
 * scheduler-free implementation.
 *
 * **Distinction between "depleted" and "never spawned".** A cell at `quantity = 0` with
 * `initial_quantity > 0` has been mined out. **No cell** at all means the node never
 * had this item. The gather reducer uses this distinction to choose between
 * `NodeResourceDepleted` and `ResourceNotAvailableHere` rejections.
 */
internal interface NodeResourceStore {

    /** Read all live resources for a node, applying lazy regen at [tick]. */
    fun read(nodeId: NodeId, tick: Long): NodeResources

    /**
     * Read a single (node, item) cell, applying lazy regen at [tick]. Returns
     * `null` if no row exists for the pair (i.e. the item never spawned here).
     */
    fun availability(nodeId: NodeId, item: ItemId, tick: Long): NodeResourceCell?

    /**
     * Decrement a single (node, item) row by [amount] at [tick]. Caller must verify
     * availability first; `decrement` throws on under-zero attempts.
     */
    fun decrement(nodeId: NodeId, item: ItemId, amount: Int, tick: Long)

    /**
     * Bulk-create initial rows. Idempotent on `(node, item)` — a re-paint of the same
     * node returns existing rows untouched, so the spawner can call this freely.
     */
    fun seed(rows: Collection<InitialResourceRow>, tick: Long)
}

/** A single (node, item) cell with current and initial quantities. */
internal data class NodeResourceCell(
    val nodeId: NodeId,
    val item: ItemId,
    val quantity: Int,
    val initialQuantity: Int,
)

/** Initial-spawn row produced by [ResourceSpawner.rollFor]. */
internal data class InitialResourceRow(
    val nodeId: NodeId,
    val item: ItemId,
    val quantity: Int,
)
