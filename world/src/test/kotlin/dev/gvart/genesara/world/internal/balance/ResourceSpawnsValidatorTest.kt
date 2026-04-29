package dev.gvart.genesara.world.internal.balance

import dev.gvart.genesara.world.Item
import dev.gvart.genesara.world.ItemCategory
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.ItemLookup
import dev.gvart.genesara.world.Terrain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertTrue

class ResourceSpawnsValidatorTest {

    private val items = StubItemLookup(setOf("WOOD", "BERRY", "STONE"))

    @Test
    fun `accepts valid spawn rules without throwing`() {
        val world = WorldDefinitionProperties(
            terrains = mapOf(
                Terrain.FOREST to TerrainProperties(
                    displayName = "Forest",
                    resourceSpawns = listOf(
                        ResourceSpawnRuleProperties("WOOD", 0.7, listOf(80, 200)),
                        ResourceSpawnRuleProperties("BERRY", 0.5, listOf(20, 80)),
                    ),
                ),
            ),
        )

        ResourceSpawnsValidator(world, items).validate()
    }

    @Test
    fun `rejects spawn rules referencing unknown items`() {
        val world = WorldDefinitionProperties(
            terrains = mapOf(
                Terrain.FOREST to TerrainProperties(
                    displayName = "Forest",
                    resourceSpawns = listOf(ResourceSpawnRuleProperties("PHANTOM", 0.5, listOf(10, 20))),
                ),
            ),
        )

        val ex = assertThrows<IllegalArgumentException> {
            ResourceSpawnsValidator(world, items).validate()
        }
        assertTrue(ex.message?.contains("PHANTOM") == true, "error must mention the unknown id")
    }

    @Test
    fun `rejects malformed quantity range — wrong size`() {
        val world = WorldDefinitionProperties(
            terrains = mapOf(
                Terrain.FOREST to TerrainProperties(
                    displayName = "Forest",
                    resourceSpawns = listOf(ResourceSpawnRuleProperties("WOOD", 0.5, listOf(10))),
                ),
            ),
        )

        assertThrows<IllegalArgumentException> {
            ResourceSpawnsValidator(world, items).validate()
        }
    }

    @Test
    fun `rejects inverted quantity range`() {
        val world = WorldDefinitionProperties(
            terrains = mapOf(
                Terrain.FOREST to TerrainProperties(
                    displayName = "Forest",
                    resourceSpawns = listOf(ResourceSpawnRuleProperties("WOOD", 0.5, listOf(200, 80))),
                ),
            ),
        )

        val ex = assertThrows<IllegalArgumentException> {
            ResourceSpawnsValidator(world, items).validate()
        }
        assertTrue(ex.message?.contains("min > max") == true)
    }

    @Test
    fun `rejects negative quantity range`() {
        val world = WorldDefinitionProperties(
            terrains = mapOf(
                Terrain.FOREST to TerrainProperties(
                    displayName = "Forest",
                    resourceSpawns = listOf(ResourceSpawnRuleProperties("WOOD", 0.5, listOf(-1, 10))),
                ),
            ),
        )

        assertThrows<IllegalArgumentException> {
            ResourceSpawnsValidator(world, items).validate()
        }
    }

    @Test
    fun `rejects spawn-chance outside the unit interval`() {
        val world = WorldDefinitionProperties(
            terrains = mapOf(
                Terrain.FOREST to TerrainProperties(
                    displayName = "Forest",
                    resourceSpawns = listOf(ResourceSpawnRuleProperties("WOOD", 1.5, listOf(10, 20))),
                ),
            ),
        )

        val ex = assertThrows<IllegalArgumentException> {
            ResourceSpawnsValidator(world, items).validate()
        }
        assertTrue(ex.message?.contains("spawn-chance") == true)
    }

    private class StubItemLookup(ids: Set<String>) : ItemLookup {
        private val byId = ids.associateWith {
            Item(
                id = ItemId(it),
                displayName = it,
                description = "",
                category = ItemCategory.RESOURCE,
                weightPerUnit = 100,
                maxStack = 100,
            )
        }
        override fun byId(id: ItemId): Item? = byId[id.value]
        override fun all(): List<Item> = byId.values.toList()
    }
}
