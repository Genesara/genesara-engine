package dev.gvart.genesara.world.internal.resources

import dev.gvart.genesara.world.Node
import dev.gvart.genesara.world.internal.balance.BalanceLookup
import org.springframework.stereotype.Component
import java.util.Random

/**
 * Rolls a node's initial resource state from its terrain's spawn rules.
 *
 * **Determinism.** The PRNG is seeded with `worldSeed XOR nodeId.value`, so:
 * - The same world produces the same map every time it's painted.
 * - A wipe-and-reseed reproduces an identical world (Redis flush, DB reset, etc.).
 * - Debug replays can reconstruct world state from id alone.
 *
 * Each rule rolls **independently**: a forest node may end up with WOOD and BERRY but
 * no HERB, or even nothing at all. Empty results are valid — some forests are barren.
 *
 * Reuses [BalanceLookup.resourceSpawnsFor] for the per-terrain rule list, so changing
 * terrain spawn YAML changes spawn behavior without touching this class.
 */
@Component
internal class ResourceSpawner(
    private val balance: BalanceLookup,
) {

    /**
     * Roll the initial resource set for [node] under [worldSeed]. Pure: same inputs →
     * same output. Caller passes the result to [NodeResourceStore.seed].
     */
    fun rollFor(node: Node, worldSeed: Long): List<InitialResourceRow> {
        val rules = balance.resourceSpawnsFor(node.terrain)
        if (rules.isEmpty()) return emptyList()
        // Mix the seed via a multiplicative constant before XOR-ing the node id. Plain
        // `worldSeed XOR nodeId` collides whenever they're equal (worldSeed=1 + first
        // node=1 → Random(0), with a well-known biased opening). 0x9E3779B97F4A7C15 is
        // the 64-bit golden-ratio constant used in SplitMix-style mixers — it spreads
        // adjacent inputs across the whole 64-bit space so neighbouring (world, node)
        // pairs don't share a PRNG seed prefix.
        val rngSeed = worldSeed * 0x9E3779B97F4A7C15UL.toLong() xor node.id.value
        val rng = Random(rngSeed)
        return rules.mapNotNull { rule ->
            // nextDouble() returns [0.0, 1.0). spawnChance == 1.0 always passes;
            // spawnChance == 0.0 always fails — both behave as expected.
            if (rng.nextDouble() >= rule.spawnChance) return@mapNotNull null
            val lo = rule.quantityRange.first
            val hi = rule.quantityRange.last
            val span = (hi - lo + 1).coerceAtLeast(1)
            val qty = lo + rng.nextInt(span)
            InitialResourceRow(nodeId = node.id, item = rule.item, quantity = qty)
        }
    }
}
