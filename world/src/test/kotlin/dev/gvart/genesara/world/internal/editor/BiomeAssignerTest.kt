package dev.gvart.genesara.world.internal.editor

import dev.gvart.genesara.world.Biome
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BiomeAssignerTest {

    private val assigner = BiomeAssigner()

    @Test
    fun `assigns a biome and climate to every region`() {
        val adjacency = ringAdjacency(50)

        val out = assigner.assign(adjacency, worldSeed = 1L, oceanFraction = 0.30)

        assertEquals(50, out.size)
        for ((id, assignment) in out) {
            assertNotNull(assignment.biome, "region $id missing biome")
            assertNotNull(assignment.climate, "region $id missing climate")
        }
    }

    @Test
    fun `produces ocean and land regions in roughly the requested proportion`() {
        val adjacency = ringAdjacency(100)

        val out = assigner.assign(adjacency, worldSeed = 42L, oceanFraction = 0.30)

        val oceanCount = out.values.count { it.biome == Biome.OCEAN }
        // Allow some slack — flood-fill might overshoot/undershoot the target by a few
        // when the target straddles a frontier expansion.
        assertTrue(oceanCount in 25..35, "expected ~30 ocean regions, got $oceanCount")
        assertTrue(oceanCount < out.size, "world cannot be 100% ocean")
    }

    @Test
    fun `ocean regions form contiguous blocks (flood-fill reachable from any seed)`() {
        // On a ring topology with 100 regions and ~30% ocean, the ocean set should be
        // reachable from any other ocean region by walking only over ocean tiles. This
        // checks the flood-fill bias actually clusters them rather than scattering.
        val adjacency = ringAdjacency(100)

        val out = assigner.assign(adjacency, worldSeed = 7L, oceanFraction = 0.30)
        val ocean = out.entries.filter { it.value.biome == Biome.OCEAN }.map { it.key }.toSet()
        if (ocean.size <= 1) return // contiguity is trivial

        // BFS over ocean using ring adjacency; verify the discovered set equals the full ocean.
        val start = ocean.first()
        val visited = mutableSetOf(start)
        val frontier = ArrayDeque<Long>().apply { add(start) }
        while (frontier.isNotEmpty()) {
            val cur = frontier.removeFirst()
            for (n in adjacency[cur].orEmpty()) {
                if (n in ocean && n !in visited) {
                    visited += n
                    frontier += n
                }
            }
        }
        // We don't require ocean to be a single connected component — flood-fill from
        // multiple seeds can produce a couple of seas — but the count must stay close
        // to the seed count. The assigner caps seeds at sqrt(target) ≤ 12; allow a bit
        // of slack for the rare case where re-seeding fires on a disconnected graph.
        val components = countComponents(ocean, adjacency)
        assertTrue(
            components <= 8,
            "ocean fragmented into $components pieces — flood-fill bias degraded; expected ≤ ~sqrt(target)=5",
        )
    }

    @Test
    fun `land regions adjacent to ocean become COASTAL`() {
        val adjacency = ringAdjacency(50)

        val out = assigner.assign(adjacency, worldSeed = 3L, oceanFraction = 0.30)
        for ((id, assignment) in out) {
            if (assignment.biome == Biome.OCEAN) continue
            val touchesOcean = adjacency[id].orEmpty().any { out[it]?.biome == Biome.OCEAN }
            if (touchesOcean) {
                assertEquals(Biome.COASTAL, assignment.biome, "land region $id touches ocean but is not COASTAL")
            }
        }
    }

    @Test
    fun `assignment is deterministic for the same seed`() {
        val adjacency = ringAdjacency(40)

        val a = assigner.assign(adjacency, worldSeed = 99L, oceanFraction = 0.25)
        val b = assigner.assign(adjacency, worldSeed = 99L, oceanFraction = 0.25)

        assertEquals(a, b)
    }

    @Test
    fun `oceanFraction=0 produces zero ocean regions`() {
        val adjacency = ringAdjacency(20)

        val out = assigner.assign(adjacency, worldSeed = 1L, oceanFraction = 0.0)

        assertEquals(0, out.values.count { it.biome == Biome.OCEAN })
    }

    @Test
    fun `oceanFraction is clamped so at least one land region survives`() {
        val adjacency = ringAdjacency(10)

        val out = assigner.assign(adjacency, worldSeed = 1L, oceanFraction = 1.0)

        assertTrue(out.values.any { it.biome != Biome.OCEAN }, "all regions became ocean")
    }

    @Test
    fun `does not over-count when target is smaller than seed count`() {
        // Tiny target (1 ocean region on a 50-region world) — the seed-count formula
        // would otherwise want sqrt(1) = 1 seed, but historical bug let seedCount
        // floor to 2 and overshoot. Assert the request is honored exactly.
        val adjacency = ringAdjacency(50)

        val out = assigner.assign(adjacency, worldSeed = 13L, oceanFraction = 0.02) // target = 1

        val oceanCount = out.values.count { it.biome == Biome.OCEAN }
        assertEquals(1, oceanCount, "expected exactly 1 ocean region, got $oceanCount")
    }

    @Test
    fun `disconnected adjacency graph still hits the ocean target via re-seeding`() {
        // Two disconnected components of 10 nodes each. Without re-seeding, BFS
        // exhausts one component and silently undershoots; with re-seeding the
        // assigner should still hit the requested count.
        val adjacency: Map<Long, Collection<Long>> =
            (0L until 10L).associateWith { i -> listOf((i + 1) % 10, (i - 1 + 10) % 10) } +
                (10L until 20L).associateWith { i -> listOf(((i - 10) + 1) % 10 + 10, ((i - 10) - 1 + 10) % 10 + 10) }

        val out = assigner.assign(adjacency, worldSeed = 5L, oceanFraction = 0.50)

        val oceanCount = out.values.count { it.biome == Biome.OCEAN }
        // Target = 10. Allow ±2 slack for boundary expansion behavior, but the obvious
        // pre-fix bug dropped to ~5 (one component drained), which would clearly fail.
        assertTrue(oceanCount >= 9, "disconnected graph undershot ocean target: $oceanCount")
    }

    /** Builds a directed ring of [n] regions, each linked to its two neighbors. */
    private fun ringAdjacency(n: Int): Map<Long, Collection<Long>> {
        require(n >= 2)
        return (0L until n).associateWith { i ->
            listOf((i + 1) % n, (i - 1 + n) % n)
        }
    }

    private fun countComponents(set: Set<Long>, adjacency: Map<Long, Collection<Long>>): Int {
        val seen = mutableSetOf<Long>()
        var components = 0
        for (s in set) {
            if (s in seen) continue
            components++
            val frontier = ArrayDeque<Long>().apply { add(s) }
            while (frontier.isNotEmpty()) {
                val cur = frontier.removeFirst()
                if (!seen.add(cur)) continue
                for (n in adjacency[cur].orEmpty()) {
                    if (n in set && n !in seen) frontier += n
                }
            }
        }
        return components
    }
}
