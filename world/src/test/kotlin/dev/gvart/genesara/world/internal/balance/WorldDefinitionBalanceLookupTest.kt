package dev.gvart.genesara.world.internal.balance

import dev.gvart.genesara.world.Terrain
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies the BalanceLookup methods that read directly off [WorldDefinitionProperties]
 * (no climate / biome / item composition). The water-source flag is the new contract for
 * Slice 4; the drink + sleep constants don't depend on YAML and are exercised through
 * reducer / passive tests.
 */
class WorldDefinitionBalanceLookupTest {

    @Test
    fun `isWaterSource is true for terrains tagged water-source=true`() {
        val lookup = WorldDefinitionBalanceLookup(
            WorldDefinitionProperties(
                terrains = mapOf(
                    Terrain.RIVER_DELTA to TerrainProperties(displayName = "River Delta", waterSource = true),
                    Terrain.FOREST to TerrainProperties(displayName = "Forest"),
                ),
            ),
        )

        assertTrue(lookup.isWaterSource(Terrain.RIVER_DELTA))
        assertFalse(lookup.isWaterSource(Terrain.FOREST))
    }

    @Test
    fun `isWaterSource is false for terrains absent from the catalog`() {
        // An unmapped terrain (no entry in YAML) defaults to non-water — the lookup must
        // not throw or return true.
        val lookup = WorldDefinitionBalanceLookup(WorldDefinitionProperties(terrains = emptyMap()))

        assertFalse(lookup.isWaterSource(Terrain.SHORELINE))
    }

    @Test
    fun `drink stamina cost is cheaper than a single gather — invariant the reducer relies on`() {
        // Drinking should never be more expensive than gathering; this is a load-bearing
        // balance assumption (an agent finding a river should never choose to gather over
        // drink for hydration). Pinning the relative ordering, not the absolute values.
        val lookup = WorldDefinitionBalanceLookup(WorldDefinitionProperties())

        assertTrue(lookup.drinkStaminaCost() > 0, "drink must cost something to prevent zero-cost spam")
        assertTrue(
            lookup.drinkStaminaCost() < lookup.gatherStaminaCost(dev.gvart.genesara.world.ItemId("WOOD")),
            "drink (${lookup.drinkStaminaCost()}) must be cheaper than gather (${lookup.gatherStaminaCost(dev.gvart.genesara.world.ItemId("WOOD"))})",
        )
    }

    @Test
    fun `default property factory does not advertise terrains as water sources`() {
        val props = TerrainProperties(displayName = "Test")
        assertFalse(props.waterSource)
        assertEquals(emptyList(), props.resourceSpawns)
        assertTrue(props.traversable)
    }

    @Test
    fun `resourceSpawnsFor returns the configured rules with quantity ranges parsed`() {
        val lookup = WorldDefinitionBalanceLookup(
            WorldDefinitionProperties(
                terrains = mapOf(
                    Terrain.FOREST to TerrainProperties(
                        displayName = "Forest",
                        resourceSpawns = listOf(
                            ResourceSpawnRuleProperties(item = "WOOD", spawnChance = 0.7, quantityRange = listOf(80, 200)),
                            ResourceSpawnRuleProperties(item = "BERRY", spawnChance = 0.5, quantityRange = listOf(20, 80)),
                        ),
                    ),
                ),
            ),
        )

        val rules = lookup.resourceSpawnsFor(Terrain.FOREST)
        assertEquals(2, rules.size)
        val wood = rules.first { it.item.value == "WOOD" }
        assertEquals(0.7, wood.spawnChance)
        assertEquals(80..200, wood.quantityRange)
    }

    @Test
    fun `resourceSpawnsFor returns empty for unmapped terrains`() {
        val lookup = WorldDefinitionBalanceLookup(WorldDefinitionProperties(terrains = emptyMap()))

        assertEquals(emptyList(), lookup.resourceSpawnsFor(Terrain.GLACIER))
    }

    @Test
    fun `isTraversable reads the terrain catalog flag, defaulting to true`() {
        val lookup = WorldDefinitionBalanceLookup(
            WorldDefinitionProperties(
                terrains = mapOf(
                    Terrain.OCEAN to TerrainProperties(displayName = "Ocean", traversable = false),
                    Terrain.FOREST to TerrainProperties(displayName = "Forest"),
                ),
            ),
        )

        assertFalse(lookup.isTraversable(Terrain.OCEAN))
        assertTrue(lookup.isTraversable(Terrain.FOREST))
        // Missing entry defaults to traversable so partial test fixtures don't accidentally
        // block all movement (matches the production lookup contract).
        assertTrue(lookup.isTraversable(Terrain.GLACIER))
    }
}
