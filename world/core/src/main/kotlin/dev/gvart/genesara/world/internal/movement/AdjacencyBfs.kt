package dev.gvart.genesara.world.internal.movement

import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.internal.worldstate.views.CoreReadView

/**
 * Hop distance from [from] to [to] over node adjacency, bounded at [maxHops].
 * Returns 0 for same-node, -1 when [to] is unreachable within the bound or
 * when [maxHops] is non-positive. Both [AttackNpcReducer] and
 * [AttackMountReducer] use this for weapon-range checks.
 */
fun hopDistance(core: CoreReadView, from: NodeId, to: NodeId, maxHops: Int): Int {
    if (from == to) return 0
    if (maxHops <= 0) return -1
    val visited = mutableSetOf(from)
    var frontier: Set<NodeId> = setOf(from)
    for (depth in 1..maxHops) {
        val next = mutableSetOf<NodeId>()
        for (nodeId in frontier) {
            val node = core.nodes[nodeId] ?: continue
            for (neighbor in node.adjacency) {
                if (neighbor == to) return depth
                if (visited.add(neighbor)) next += neighbor
            }
        }
        if (next.isEmpty()) return -1
        frontier = next
    }
    return -1
}

/**
 * BFS from [origin] out to [maxHops] (>=1), returning every node reachable
 * within that radius — excluding [origin] and any [excludedNode] (typically
 * the attacker's node, treated as a wall so a creature can't flee back into
 * its attacker). Result is sorted by NodeId for deterministic selection.
 */
fun fleeCandidates(
    core: CoreReadView,
    origin: NodeId,
    excludedNode: NodeId,
    maxHops: Int,
): List<NodeId> {
    val cap = maxHops.coerceAtLeast(1)
    val visited = mutableSetOf(origin, excludedNode)
    val reached = mutableListOf<NodeId>()
    var frontier: Set<NodeId> = setOf(origin)
    repeat(cap) {
        val next = mutableSetOf<NodeId>()
        for (nodeId in frontier) {
            val node = core.nodes[nodeId] ?: continue
            for (neighbor in node.adjacency) {
                if (visited.add(neighbor)) {
                    next += neighbor
                    reached += neighbor
                }
            }
        }
        if (next.isEmpty()) return reached.sortedBy { it.value }
        frontier = next
    }
    return reached.sortedBy { it.value }
}
