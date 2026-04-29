package dev.gvart.genesara.world.internal.editor

import java.util.Random

/**
 * Cumulative-weight picker used by world-painting code. Constant-time setup, O(N)
 * pick — fine at the small N (single-digit dozens) we actually use it on.
 *
 * All weights must be strictly positive. The picker is non-empty by construction.
 */
internal class WeightedPicker<T>(entries: List<Pair<T, Double>>) {
    private val cumulative: DoubleArray
    private val items: List<T>
    private val total: Double

    init {
        require(entries.isNotEmpty()) { "WeightedPicker requires at least one entry" }
        items = entries.map { it.first }
        var running = 0.0
        cumulative = DoubleArray(entries.size)
        entries.forEachIndexed { i, (_, w) ->
            require(w > 0.0) { "weight must be > 0, got $w" }
            running += w
            cumulative[i] = running
        }
        total = running
    }

    fun pick(rng: Random): T {
        val pick = rng.nextDouble() * total
        for (i in cumulative.indices) {
            if (pick <= cumulative[i]) return items[i]
        }
        return items.last()
    }
}
