package dev.gvart.genesara.world.internal.balance

import dev.gvart.genesara.world.Node

/**
 * Admin-time seeder used by the world editor to roll initial resource availability
 * for a freshly-painted set of nodes. Lives in core so [editor] does not need to
 * import the economy-zone resource store / spawner directly.
 */
internal interface WorldResourceSeeder {
    fun seedResourcesFor(nodes: List<Node>, worldSeed: Long, tick: Long)
}
