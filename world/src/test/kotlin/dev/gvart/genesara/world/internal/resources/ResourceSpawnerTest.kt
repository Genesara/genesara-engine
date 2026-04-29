package dev.gvart.genesara.world.internal.resources

import dev.gvart.genesara.world.Biome
import dev.gvart.genesara.world.Climate
import dev.gvart.genesara.world.Gauge
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.Node
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.RegionId
import dev.gvart.genesara.world.ResourceSpawnRule
import dev.gvart.genesara.world.Terrain
import dev.gvart.genesara.world.internal.balance.BalanceLookup
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResourceSpawnerTest {

    private val wood = ItemId("WOOD")
    private val berry = ItemId("BERRY")
    private val gem = ItemId("GEM")

    private fun forestNode(id: Long) =
        Node(NodeId(id), RegionId(1L), q = 0, r = 0, terrain = Terrain.FOREST, adjacency = emptySet())

    private fun balance(rules: Map<Terrain, List<ResourceSpawnRule>>) = object : BalanceLookup {
        override fun moveStaminaCost(biome: Biome, climate: Climate, terrain: Terrain) = 1
        override fun staminaRegenPerTick(climate: Climate) = 0
        override fun resourceSpawnsFor(terrain: Terrain): List<ResourceSpawnRule> =
            rules[terrain].orEmpty()
        override fun gatherStaminaCost(item: ItemId): Int = 5
        override fun gatherYield(item: ItemId): Int = 1
        override fun gaugeDrainPerTick(gauge: Gauge): Int = 0
        override fun gaugeLowThreshold(gauge: Gauge): Int = 25
        override fun starvationDamagePerTick(): Int = 0
        override fun isWaterSource(terrain: Terrain): Boolean = false
        override fun drinkStaminaCost(): Int = 1
        override fun drinkThirstRefill(): Int = 25
        override fun sleepRegenPerOfflineTick(): Int = 0
        override fun isTraversable(terrain: Terrain): Boolean = true
    }

    @Test
    fun `same seed and node reproduces the same roll`() {
        val spawner = ResourceSpawner(
            balance(
                mapOf(
                    Terrain.FOREST to listOf(
                        ResourceSpawnRule(wood, 0.6, 50..200),
                        ResourceSpawnRule(berry, 0.6, 20..80),
                    ),
                ),
            ),
        )
        val node = forestNode(id = 42L)

        val first = spawner.rollFor(node, worldSeed = 17L)
        val second = spawner.rollFor(node, worldSeed = 17L)

        assertEquals(first, second, "deterministic seeding should reproduce the same rolls")
    }

    @Test
    fun `different seeds produce different distributions`() {
        // Statistical sanity: across many nodes with the same rule, two different seeds
        // disagree on at least some rolls. Confirms the seed actually flows through to
        // PRNG state — not a strong test of randomness, just a smoke test that swapping
        // the world seed observably changes the world.
        val spawner = ResourceSpawner(
            balance(mapOf(Terrain.FOREST to listOf(ResourceSpawnRule(wood, 0.5, 50..200)))),
        )
        val nodes = (1L..30L).map(::forestNode)

        val seedA = nodes.flatMap { spawner.rollFor(it, worldSeed = 1L) }
        val seedB = nodes.flatMap { spawner.rollFor(it, worldSeed = 1_000L) }

        assertTrue(seedA != seedB, "different seeds should produce different roll lists")
    }

    @Test
    fun `spawn-chance 1_0 always produces an entry, 0_0 never does`() {
        val always = ResourceSpawner(
            balance(mapOf(Terrain.FOREST to listOf(ResourceSpawnRule(wood, 1.0, 100..100)))),
        )
        val never = ResourceSpawner(
            balance(mapOf(Terrain.FOREST to listOf(ResourceSpawnRule(wood, 0.0, 100..100)))),
        )

        // Across many nodes/seeds: never returns nothing, always returns one row each.
        repeat(20) { i ->
            val n = forestNode(id = i.toLong() + 100)
            assertEquals(1, always.rollFor(n, worldSeed = i.toLong()).size)
            assertEquals(0, never.rollFor(n, worldSeed = i.toLong()).size)
        }
    }

    @Test
    fun `rolled quantity always falls within the configured range`() {
        val spawner = ResourceSpawner(
            balance(mapOf(Terrain.FOREST to listOf(ResourceSpawnRule(wood, 1.0, 50..60)))),
        )
        repeat(50) { i ->
            val n = forestNode(id = i.toLong())
            val row = spawner.rollFor(n, worldSeed = i.toLong()).single()
            assertTrue(row.quantity in 50..60, "expected qty in [50,60], got ${row.quantity}")
        }
    }

    @Test
    fun `terrain with no spawn rules yields an empty roll`() {
        val spawner = ResourceSpawner(balance(emptyMap()))
        val node = forestNode(id = 1L)

        assertEquals(emptyList(), spawner.rollFor(node, worldSeed = 99L))
    }

    @Test
    fun `each rule rolls independently — partial spawn is allowed`() {
        // The point: getting "WOOD but not BERRY" (or vice versa) is reachable, i.e.
        // the rolls are genuinely independent rather than locked together. Across many
        // seeds the outcome set should contain at least three of the four possibilities
        // (∅, {WOOD}, {BERRY}, {WOOD,BERRY}). Two-out-of-four is enough to prove
        // independence isn't the same as "always both" or "always neither".
        val spawner = ResourceSpawner(
            balance(
                mapOf(
                    Terrain.FOREST to listOf(
                        ResourceSpawnRule(wood, 0.5, 50..50),
                        ResourceSpawnRule(berry, 0.5, 20..20),
                    ),
                ),
            ),
        )
        val outcomes = (1L..400L).map { seed ->
            spawner.rollFor(forestNode(id = 1L), worldSeed = seed * 7919L)
                .map { it.item }
                .toSet()
        }.toSet()

        assertTrue(
            outcomes.size >= 3,
            "expected at least 3 distinct outcome shapes across many seeds, got: $outcomes",
        )
    }
}
