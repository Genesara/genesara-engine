package dev.gvart.genesara.world.internal.editor

import dev.gvart.genesara.world.Biome
import dev.gvart.genesara.world.Climate
import org.springframework.stereotype.Component
import java.util.ArrayDeque
import java.util.Random
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Per-region biome + climate assignment used at world-creation time.
 *
 * The roadmap target for Phase 0 is "single continent (Eurasia-equivalent), ~500 nodes,
 * biome assignment, plus ocean fringe." We don't reshape the icosphere globe; instead we
 * paint a configurable fraction (~30%) of regions as [Biome.OCEAN], biased by flood-fill
 * from a small set of randomly-picked seeds. The remaining land regions get a weighted
 * land biome, with one nicety: any land region adjacent to ocean is forced to
 * [Biome.COASTAL] so beachheads make sense to the agent walking around them.
 *
 * Output is deterministic on `(worldId, regionCount)` so two worlds with the same id
 * paint the same way — helpful for debugging and reproducible test fixtures.
 */
@Component
internal class BiomeAssigner {

    /**
     * Assigns a `(biome, climate)` to every region in [adjacency]. The map keys are
     * arbitrary opaque region identifiers (typically jOOQ-returned BIGSERIAL ids); the
     * algorithm only cares that adjacency is consistent.
     *
     * @param adjacency directed neighbor map; should be symmetric (each edge listed both
     *   ways) but the assigner tolerates one-directional edges by treating the union.
     * @param worldSeed seed for the deterministic RNG.
     * @param oceanFraction target fraction of regions to mark as ocean. Clamped to
     *   `[0.0, 0.95]` so at least one land region survives.
     */
    fun assign(
        adjacency: Map<Long, Collection<Long>>,
        worldSeed: Long,
        oceanFraction: Double = DEFAULT_OCEAN_FRACTION,
    ): Map<Long, Assignment> {
        require(adjacency.isNotEmpty()) { "adjacency map cannot be empty" }
        val clamped = oceanFraction.coerceIn(0.0, MAX_OCEAN_FRACTION)
        val rng = Random(worldSeed)

        val symmetric = symmetrize(adjacency)
        val regionIds = symmetric.keys.toList().sorted()
        val total = regionIds.size
        val targetOcean = (total * clamped).roundToInt().coerceIn(0, total - 1)

        val ocean = pickOceanRegions(regionIds, symmetric, targetOcean, rng)
        val biomes = HashMap<Long, Biome>(total)
        for (id in regionIds) {
            biomes[id] = if (id in ocean) Biome.OCEAN else WEIGHTED_LAND.pick(rng)
        }
        // Pin land regions adjacent to ocean as COASTAL — gives beachheads consistent
        // resources and makes the shore visually meaningful in the dashboard.
        for (id in regionIds) {
            if (biomes[id] == Biome.OCEAN) continue
            val touchesOcean = symmetric[id].orEmpty().any { biomes[it] == Biome.OCEAN }
            if (touchesOcean) biomes[id] = Biome.COASTAL
        }
        return regionIds.associateWith { Assignment(biomes[it]!!, climateFor(biomes[it]!!, rng)) }
    }

    private fun pickOceanRegions(
        ids: List<Long>,
        adjacency: Map<Long, Set<Long>>,
        target: Int,
        rng: Random,
    ): Set<Long> {
        if (target == 0) return emptySet()
        // Seeds: roughly sqrt(target) growth points, capped at 12, never more than the
        // target itself (otherwise we'd plant more seeds than the budget allows and
        // overshoot before the BFS ever runs). Keeps oceans contiguous (a couple of
        // seas) rather than salt-and-pepper noise.
        val seedCount = max(1, kotlin.math.sqrt(target.toDouble()).toInt())
            .coerceAtMost(12)
            .coerceAtMost(target)
        val seeds = ids.shuffled(rng).take(seedCount)
        val ocean = HashSet<Long>(target)
        val frontier = ArrayDeque<Long>()
        seeds.forEach {
            ocean += it
            frontier += it
        }
        // Re-seed from any unvisited region whenever the BFS frontier empties — handles
        // disconnected adjacency graphs (or seed clusters that share neighborhoods)
        // without silently undershooting the requested ocean budget.
        while (ocean.size < target) {
            if (frontier.isEmpty()) {
                val nextSeed = ids.firstOrNull { it !in ocean } ?: break
                ocean += nextSeed
                frontier += nextSeed
                continue
            }
            val current = frontier.poll()
            val neighbors = adjacency[current].orEmpty()
                .filter { it !in ocean }
                .shuffled(rng)
            for (next in neighbors) {
                if (ocean.size >= target) break
                ocean += next
                frontier += next
            }
        }
        return ocean
    }

    private fun symmetrize(adjacency: Map<Long, Collection<Long>>): Map<Long, Set<Long>> {
        val out = HashMap<Long, MutableSet<Long>>(adjacency.size)
        for ((from, ns) in adjacency) {
            out.getOrPut(from) { HashSet() }.addAll(ns)
            for (to in ns) {
                out.getOrPut(to) { HashSet() }.add(from)
            }
        }
        return out
    }

    private fun climateFor(biome: Biome, rng: Random): Climate = when (biome) {
        Biome.OCEAN -> Climate.OCEANIC
        Biome.COASTAL -> Climate.OCEANIC
        Biome.DESERT -> if (rng.nextBoolean()) Climate.ARID else Climate.SEMI_ARID
        Biome.TUNDRA -> if (rng.nextBoolean()) Climate.ARCTIC else Climate.SUBARCTIC
        Biome.MOUNTAIN -> Climate.HIGHLAND
        Biome.SWAMP -> Climate.SUBTROPICAL
        Biome.RUINS -> Climate.CONTINENTAL
        Biome.PLAINS, Biome.FOREST -> WEIGHTED_TEMPERATE_CLIMATES.pick(rng)
    }

    /** Result of a single region assignment: the biome and a sampled climate. */
    data class Assignment(val biome: Biome, val climate: Climate)

    private companion object {
        const val DEFAULT_OCEAN_FRACTION = 0.30
        const val MAX_OCEAN_FRACTION = 0.95

        // Weighted land biomes — Plains and Forest dominate, Swamp / Ruins are rare.
        private val WEIGHTED_LAND = WeightedPicker(
            listOf(
                Biome.PLAINS to 30.0,
                Biome.FOREST to 25.0,
                Biome.MOUNTAIN to 12.0,
                Biome.DESERT to 8.0,
                Biome.TUNDRA to 8.0,
                Biome.SWAMP to 4.0,
                Biome.RUINS to 2.0,
                // COASTAL is overlaid post-pass for ocean-adjacent regions; leaving a
                // small intrinsic weight gives inland "lake-like" coastal pockets.
                Biome.COASTAL to 3.0,
            ),
        )

        private val WEIGHTED_TEMPERATE_CLIMATES = WeightedPicker(
            listOf(
                Climate.CONTINENTAL to 5.0,
                Climate.OCEANIC to 3.0,
                Climate.MEDITERRANEAN to 2.0,
                Climate.SUBTROPICAL to 1.0,
                Climate.TROPICAL to 1.0,
            ),
        )
    }
}
