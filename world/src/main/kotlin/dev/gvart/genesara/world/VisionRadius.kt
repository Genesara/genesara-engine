package dev.gvart.genesara.world

import dev.gvart.genesara.player.Agent

/**
 * Per-agent sight radius in node hops; canonical formula in `docs/lore/mechanics-reference.md` §14.
 *
 * [activeBuildingsAtCurrentNode] is the slice of buildings at the agent's current node (typically
 * already fetched by the caller for other purposes — e.g. `look_around` reads the buildings layer
 * for projection anyway). The implementation inspects it for vision-contributing structures
 * (WATCHTOWER → +2 rings); callers that do not care about the building contribution may pass an
 * empty list, which costs no I/O.
 */
interface VisionRadius {
    fun radiusFor(
        agent: Agent,
        currentNode: NodeId,
        activeBuildingsAtCurrentNode: List<Building> = emptyList(),
    ): Int
}
