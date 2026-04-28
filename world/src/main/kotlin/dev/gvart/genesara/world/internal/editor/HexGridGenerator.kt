package dev.gvart.genesara.world.internal.editor

import dev.gvart.genesara.world.Terrain
import org.springframework.stereotype.Component
import java.util.Random
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Per-tile shape used by the hex grid generator. The persistence layer maps this onto rows in
 * `nodes` (q, r, terrain).
 */
data class HexTile(val q: Int, val r: Int, val terrain: Terrain)

/**
 * Deterministic hex-grid generator used to lazy-seed a region's tactical map on first request.
 *
 * The contract explicitly allows non-parity with the frontend's simplex-noise generator. We pick
 * terrain via a seeded weighted-random distribution and bias the outer 20% ring toward the
 * containing region's biome at ~60% probability, preserving visual continuity with the globe.
 */
@Component
internal class HexGridGenerator {

    fun generate(
        worldId: Long,
        sphereIndex: Int,
        radius: Int,
        biomeHint: Terrain? = null,
    ): List<HexTile> {
        require(radius in 1..80) { "radius must be in [1, 80], got $radius" }
        val seed = "w$worldId-n$sphereIndex".hashCode().toLong()
        val rng = Random(seed)
        val edgeBand = max(1, (radius * 0.2).toInt())

        val tiles = mutableListOf<HexTile>()
        for (q in -radius..radius) {
            val rMin = max(-radius, -q - radius)
            val rMax = min(radius, -q + radius)
            for (r in rMin..rMax) {
                val dist = (abs(q) + abs(r) + abs(q + r)) / 2
                val isOuter = dist > radius - edgeBand
                val terrain = when {
                    biomeHint != null && isOuter && rng.nextDouble() < 0.6 -> biomeHint
                    else -> WEIGHTED_TERRAINS.pick(rng)
                }
                tiles += HexTile(q, r, terrain)
            }
        }
        return tiles
    }

    private companion object {
        private val WEIGHTED_TERRAINS = WeightedPicker(
            listOf(
                Terrain.PLAINS to 18.0,
                Terrain.MEADOW to 10.0,
                Terrain.FOREST to 14.0,
                Terrain.BIRCH_FOREST to 4.0,
                Terrain.HILLS to 8.0,
                Terrain.FOOTHILLS to 4.0,
                Terrain.MOUNTAIN to 6.0,
                Terrain.ALPINE to 2.0,
                Terrain.RIVER_DELTA to 3.0,
                Terrain.WETLANDS to 3.0,
                Terrain.SWAMP to 2.0,
                Terrain.DESERT to 4.0,
                Terrain.ICE_TUNDRA to 2.0,
                Terrain.COASTAL to 4.0,
                Terrain.SHORELINE to 3.0,
                Terrain.FOREST_EDGE to 5.0,
                Terrain.RAINFOREST to 2.0,
                Terrain.CANYON to 1.0,
                Terrain.CLIFFSIDE to 1.0,
                Terrain.SACRED_GROVE to 0.5,
                Terrain.ANCIENT_RUINS to 0.5,
                Terrain.CRYSTAL_CAVES to 0.3,
                Terrain.CURSED_LAND to 0.3,
                Terrain.BLIGHTED to 0.3,
            ),
        )
    }
}

private class WeightedPicker<T>(entries: List<Pair<T, Double>>) {
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
