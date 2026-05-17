package dev.gvart.genesara.world.economy.internal.resources

import dev.gvart.genesara.world.Node
import dev.gvart.genesara.world.internal.balance.WorldResourceSeeder
import org.springframework.stereotype.Component
import dev.gvart.genesara.world.internal.resources.NodeResourceStore

@Component
class JooqWorldResourceSeeder(
    private val spawner: ResourceSpawner,
    private val store: NodeResourceStore,
) : WorldResourceSeeder {

    override fun seedResourcesFor(nodes: List<Node>, worldSeed: Long, tick: Long) {
        if (nodes.isEmpty()) return
        val rolls = nodes.flatMap { spawner.rollFor(it, worldSeed) }
        if (rolls.isEmpty()) return
        store.seed(rolls, tick = tick)
    }
}
