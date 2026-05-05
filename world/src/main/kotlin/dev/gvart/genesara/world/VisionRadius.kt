package dev.gvart.genesara.world

import dev.gvart.genesara.player.Agent

/** Per-agent sight radius in node hops; canonical formula in `docs/lore/mechanics-reference.md` §14. */
interface VisionRadius {
    fun radiusFor(agent: Agent, currentNode: NodeId): Int
}
