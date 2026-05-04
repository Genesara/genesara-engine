package dev.gvart.genesara.world

import java.util.UUID

/**
 * Read-only public surface for buildings, consumed by projections (look_around,
 * inspect) and cross-cutting reducers (movement, drink). Separated from
 * [BuildingsStore] so callers that should not write — every consumer outside
 * `internal/buildings` — only see the read methods.
 *
 * Same separation as [ItemLookup] vs the underlying item catalog, and
 * [StarterNodeLookup] vs the starter-nodes table.
 */
// TODO(#9-slice8): wire `JooqBuildingsLookup` (or delegate to `BuildingsStore`)
//   when the look_around projection lands. Today this interface has no
//   Spring-managed impl — no consumer yet, so the empty graph is intentional.
interface BuildingsLookup {

    fun byId(id: UUID): Building?

    fun byNode(node: NodeId): List<Building>

    /**
     * **Batched** read for the look_around hot path — one round-trip per call,
     * not one per visible node. Always prefer this over a per-id loop on the
     * read path.
     */
    fun byNodes(nodes: Set<NodeId>): Map<NodeId, List<Building>>

    /**
     * Active (not under construction) buildings on [node] whose category hint
     * matches [hint]. Used by the cross-cutting reducers (movement, drink) to
     * ask "is there a road here?" / "is there a well here?" / "is this a
     * crafting station for #8?" without coupling to specific [BuildingType]
     * variants.
     */
    fun activeStationsAt(node: NodeId, hint: BuildingCategoryHint): List<Building>
}
