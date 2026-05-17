package dev.gvart.genesara.world.internal.testsupport

import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.internal.vision.VisionBlockerCache

/**
 * In-memory `VisionBlockerCache` for unit tests. Tests drive `recomputeForNode`
 * through a per-node supplier or pre-populate via [putForTest] — there's no DB
 * to read from in the unit-test plane.
 */
internal class InMemoryVisionBlockerCache(
    private val recompute: (NodeId) -> Int = { 0 },
) : VisionBlockerCache {

    private val totals: MutableMap<NodeId, Int> = mutableMapOf()

    fun putForTest(node: NodeId, total: Int) {
        if (total > 0) totals[node] = total else totals.remove(node)
    }

    override fun blockerHeights(nodes: Set<NodeId>): Map<NodeId, Int> =
        nodes.mapNotNull { node ->
            totals[node]?.let { node to it }
        }.toMap()

    override fun recomputeForNode(nodeId: NodeId) {
        val newTotal = recompute(nodeId)
        putForTest(nodeId, newTotal)
    }

    override fun seedAll() { /* tests pre-populate via putForTest */ }

    override fun flush() { totals.clear() }
}
