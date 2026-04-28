package dev.gvart.genesara.world.internal.editor

import dev.gvart.genesara.world.Terrain
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HexGridGeneratorTest {

    private val gen = HexGridGenerator()

    @Test
    fun `same seed produces identical output across calls`() {
        val a = gen.generate(worldId = 1, sphereIndex = 0, radius = 12)
        val b = gen.generate(worldId = 1, sphereIndex = 0, radius = 12)
        assertEquals(a, b)
    }

    @Test
    fun `different worldId or sphereIndex produces different output`() {
        val a = gen.generate(worldId = 1, sphereIndex = 0, radius = 12)
        val b = gen.generate(worldId = 1, sphereIndex = 1, radius = 12)
        val c = gen.generate(worldId = 2, sphereIndex = 0, radius = 12)
        assertTrue(a != b, "different sphereIndex should diverge")
        assertTrue(a != c, "different worldId should diverge")
    }

    @Test
    fun `tile count matches the hexagonal radius formula 1 + 3R(R+1)`() {
        val r = 12
        val tiles = gen.generate(worldId = 1, sphereIndex = 0, radius = r)
        assertEquals(1 + 3 * r * (r + 1), tiles.size)
    }

    @Test
    fun `outer ring is biased toward the biome hint at majority probability`() {
        val radius = 20
        val edgeBand = max(1, (radius * 0.2).toInt())
        val hint = Terrain.FOREST
        val tiles = gen.generate(worldId = 7, sphereIndex = 3, radius = radius, biomeHint = hint)
        val outer = tiles.filter { tile ->
            val dist = (abs(tile.q) + abs(tile.r) + abs(tile.q + tile.r)) / 2
            dist > radius - edgeBand
        }
        val matching = outer.count { it.terrain == hint }
        // 60% biasing — give a generous margin (>=45%) to absorb seeded variance.
        assertTrue(matching.toDouble() / outer.size >= 0.45, "expected outer ring to lean toward $hint, got $matching / ${outer.size}")
    }
}
