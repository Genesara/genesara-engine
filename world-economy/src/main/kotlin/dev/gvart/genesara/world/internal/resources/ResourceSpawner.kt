package dev.gvart.genesara.world.internal.resources

import dev.gvart.genesara.world.Node
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.internal.balance.BalanceLookup
import org.springframework.stereotype.Component
import java.util.Random

/**
 * Rolls a node's initial resource state from its terrain's spawn rules.
 *
 * Determinism: the PRNG is seeded with a SplitMix-style mix of `worldSeed` and
 * `nodeId`, so the same world produces the same map every time it's painted, a wipe-
 * and-reseed reproduces an identical world, and debug replays can reconstruct world
 * state from id alone.
 *
 * Each rule rolls independently — a forest node may end up with WOOD and BERRY but no
 * HERB, or nothing at all. Empty results are valid; some forests are barren.
 *
 * Reuses [BalanceLookup.resourceSpawnsFor] for the per-terrain rule list, so changing
 * spawn YAML changes spawn behavior without touching this class.
 */
@Component
internal class ResourceSpawner(
    private val balance: BalanceLookup,
) {

    fun rollFor(node: Node, worldSeed: Long): List<InitialResourceRow> {
        val rules = balance.resourceSpawnsFor(node.terrain)
        if (rules.isEmpty()) return emptyList()
        val rng = Random(mixSeed(worldSeed, node.id))
        return rules.mapNotNull { rule ->
            if (rng.nextDouble() >= rule.spawnChance) return@mapNotNull null
            val lo = rule.quantityRange.first
            val hi = rule.quantityRange.last
            val span = (hi - lo + 1).coerceAtLeast(1)
            val qty = lo + rng.nextInt(span)
            InitialResourceRow(nodeId = node.id, item = rule.item, quantity = qty)
        }
    }

    /**
     * Plain `worldSeed XOR nodeId` collides whenever the two are equal. Mixing through
     * the 64-bit golden-ratio constant (SplitMix-style) spreads adjacent inputs across
     * the whole 64-bit space so neighbouring `(world, node)` pairs don't share a PRNG
     * seed prefix.
     */
    private fun mixSeed(worldSeed: Long, nodeId: NodeId): Long =
        worldSeed * GOLDEN_RATIO_64 xor nodeId.value

    private companion object {
        private val GOLDEN_RATIO_64 = 0x9E3779B97F4A7C15UL.toLong()
    }
}
