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
 * Phase 0 paints `~30%` of regions as [Biome.OCEAN] via flood-fill from a few random
 * seeds; remaining land regions get a weighted biome and any land touching ocean is
 * upgraded to [Biome.COASTAL] so beachheads make sense to the agent walking around them.
 *
 * Output is deterministic on `(worldId, regionCount)` so two worlds with the same id
 * paint identically — useful for debugging and reproducible test fixtures.
 */
@Component
internal class BiomeAssigner {

    /**
     * Assigns a `(biome, climate)` to every region in [adjacency]. The map keys are
     * arbitrary opaque region identifiers; the algorithm only needs adjacency to be
     * consistent.
     *
     * @param adjacency directed neighbor map; should be symmetric, but the assigner
     *   tolerates one-directional edges by treating the union.
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
        upgradeOceanAdjacentLandToCoastal(regionIds, symmetric, biomes)
        return regionIds.associateWith { Assignment(biomes[it]!!, climateFor(biomes[it]!!, rng)) }
    }

    private fun upgradeOceanAdjacentLandToCoastal(
        regionIds: List<Long>,
        symmetric: Map<Long, Set<Long>>,
        biomes: MutableMap<Long, Biome>,
    ) {
        for (id in regionIds) {
            if (biomes[id] == Biome.OCEAN) continue
            val touchesOcean = symmetric[id].orEmpty().any { biomes[it] == Biome.OCEAN }
            if (touchesOcean) biomes[id] = Biome.COASTAL
        }
    }

    /**
     * Plants ~`sqrt(target)` BFS seeds (capped at 12), then flood-fills until the budget
     * is exhausted. When the frontier empties before reaching the budget — disconnected
     * graphs or shared-neighborhood seed clusters — picks a fresh unvisited region as
     * the next seed so we don't silently undershoot.
     */
    private fun pickOceanRegions(
        ids: List<Long>,
        adjacency: Map<Long, Set<Long>>,
        target: Int,
        rng: Random,
    ): Set<Long> {
        if (target == 0) return emptySet()
        val seedCount = max(1, kotlin.math.sqrt(target.toDouble()).toInt())
            .coerceAtMost(MAX_OCEAN_SEEDS)
            .coerceAtMost(target)
        val seeds = ids.shuffled(rng).take(seedCount)
        val ocean = HashSet<Long>(target)
        val frontier = ArrayDeque<Long>()
        seeds.forEach {
            ocean += it
            frontier += it
        }
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

    data class Assignment(val biome: Biome, val climate: Climate)

    private companion object {
        const val DEFAULT_OCEAN_FRACTION = 0.30
        const val MAX_OCEAN_FRACTION = 0.95
        const val MAX_OCEAN_SEEDS = 12

        /**
         * COASTAL carries a small intrinsic weight so inland "lake-like" coastal pockets
         * appear; the post-pass that pins ocean-adjacent land to COASTAL adds the rest.
         */
        private val WEIGHTED_LAND = WeightedPicker(
            listOf(
                Biome.PLAINS to 30.0,
                Biome.FOREST to 25.0,
                Biome.MOUNTAIN to 12.0,
                Biome.DESERT to 8.0,
                Biome.TUNDRA to 8.0,
                Biome.SWAMP to 4.0,
                Biome.RUINS to 2.0,
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
