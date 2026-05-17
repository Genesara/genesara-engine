package dev.gvart.genesara.world.internal.npc

import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.internal.worldstate.WorldState

/**
 * Per-tick active set for NPC simulation: every node within [hopRadius]
 * adjacency hops of any anchor in [anchors], inclusive. Matches the BFS
 * expansion the AI sweep and the active-set NPC loader both consume — keeping
 * one definition prevents drift between "what gets loaded" and "what gets
 * simulated."
 */
fun activeNodeSet(
    state: WorldState,
    anchors: Collection<NodeId>,
    hopRadius: Int,
): Set<NodeId> {
    if (anchors.isEmpty() || hopRadius < 0) return emptySet()
    val visited = anchors.toMutableSet()
    if (hopRadius == 0) return visited
    var frontier: Set<NodeId> = visited.toSet()
    repeat(hopRadius) {
        val next = mutableSetOf<NodeId>()
        for (nodeId in frontier) {
            val node = state.nodes[nodeId] ?: continue
            for (neighbor in node.adjacency) {
                if (visited.add(neighbor)) next += neighbor
            }
        }
        if (next.isEmpty()) return visited
        frontier = next
    }
    return visited
}

/**
 * BFS hop distance from [from] to [to] over [WorldState] adjacency, capped at
 * [maxHops]. Returns -1 when [to] is unreachable within [maxHops]. 0 = same
 * node.
 */
fun hopDistance(
    state: WorldState,
    from: NodeId,
    to: NodeId,
    maxHops: Int,
): Int {
    if (from == to) return 0
    if (maxHops <= 0) return -1
    val visited = mutableSetOf(from)
    var frontier: Set<NodeId> = setOf(from)
    for (depth in 1..maxHops) {
        val next = mutableSetOf<NodeId>()
        for (nodeId in frontier) {
            val node = state.nodes[nodeId] ?: continue
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
