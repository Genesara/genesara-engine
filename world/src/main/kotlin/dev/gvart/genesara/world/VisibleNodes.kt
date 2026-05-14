package dev.gvart.genesara.world

import dev.gvart.genesara.player.Agent

/**
 * Per-agent line-of-sight resolver. Returns every node the agent can see from
 * [currentNode] (always including [currentNode] itself) under the "silhouette"
 * rule: an intermediate tile blocks vision when its blocking height (terrain
 * height + active walls + closed gates) exceeds the observer's effective
 * height (own-tile terrain + watchtower bonus); the wall tile itself is still
 * visible to its observer.
 *
 * [activeBuildingsAtCurrentNode] is an optional caller-fetched fast-path —
 * empty list means the helper looks it up itself.
 */
interface VisibleNodes {
    fun visibleNodesFor(
        agent: Agent,
        currentNode: NodeId,
        activeBuildingsAtCurrentNode: List<Building> = emptyList(),
    ): Set<NodeId>
}
