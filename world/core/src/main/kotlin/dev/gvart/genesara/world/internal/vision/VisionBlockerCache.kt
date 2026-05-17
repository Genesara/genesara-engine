package dev.gvart.genesara.world.internal.vision

import dev.gvart.genesara.world.NodeId

/**
 * Per-tile cache of the integer "intermediate sight-blocking height" — the sum of
 * `BuildingDef.sightBlockerHeight` over the ACTIVE sight-blocking buildings on
 * the tile, with closed GATEs included and open GATEs excluded. The vision
 * helper combines this with the tile's terrain height to decide whether the
 * tile blocks an observer's line of sight.
 *
 * **Eager-warmed** at world load (and on every world-config reload); every
 * sight-affecting reducer (build complete, gate toggle, future wall decay) calls
 * [recomputeForNode] so the cache stays consistent with the DB without lazy
 * cold-miss code paths in the read helper. Missing fields therefore mean "0
 * blockers" — not "unknown, fall back to DB."
 */
interface VisionBlockerCache {

    /**
     * Batched read for an LOS BFS candidate set. Returns a map of every node
     * with a non-zero blocker total; nodes absent from the map have no
     * blockers (effective value 0).
     */
    fun blockerHeights(nodes: Set<NodeId>): Map<NodeId, Int>

    /**
     * Re-derives the blocker total for [nodeId] from the source of truth and
     * writes it back. Called by reducers that change a tile's blocker state
     * (build complete, gate toggle). Idempotent.
     *
     * TODO(#wall-destruction): when a destruction / decay reducer flips an
     *   ACTIVE wall to a non-ACTIVE status, it must call this. The seam exists
     *   today; only the producing reducer is missing.
     */
    fun recomputeForNode(nodeId: NodeId)

    /**
     * Wipes the cache, then re-seeds every node with a non-zero total from the
     * DB. Fired at startup and on every world-config reload, so the read path
     * never has to fall back to the DB.
     */
    fun seedAll()

    /** Drops every cached entry without re-seeding. Used by tests. */
    fun flush()
}
