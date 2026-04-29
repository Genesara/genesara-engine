package dev.gvart.genesara.world.internal.balance

import dev.gvart.genesara.player.Skill
import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.player.SkillLookup
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

        ResourceSpawnsValidator(world, items, EmptySkillLookup).validate()
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
            ResourceSpawnsValidator(world, items, EmptySkillLookup).validate()
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
            ResourceSpawnsValidator(world, items, EmptySkillLookup).validate()
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
            ResourceSpawnsValidator(world, items, EmptySkillLookup).validate()
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
            ResourceSpawnsValidator(world, items, EmptySkillLookup).validate()
        }
    }

    @Test
    fun `rejects items declaring a gathering-skill that's not in the catalog`() {
        // Items declaring a phantom skill would silently no-op every XP grant — fail fast.
        val itemsWithBadSkill = StubItemLookup(setOf("WOOD"), gatheringSkillFor = mapOf("WOOD" to "PHANTOM_SKILL"))
        val knownSkills = StubSkillLookup(setOf("FORAGING")) // PHANTOM_SKILL not present

        val ex = assertThrows<IllegalArgumentException> {
            ResourceSpawnsValidator(WorldDefinitionProperties(), itemsWithBadSkill, knownSkills).validate()
        }
        assertTrue(ex.message?.contains("PHANTOM_SKILL") == true, "error must mention the unknown skill id")
    }

    @Test
    fun `accepts items declaring a gathering-skill that's in the catalog`() {
        val itemsWithGoodSkill = StubItemLookup(setOf("WOOD"), gatheringSkillFor = mapOf("WOOD" to "LUMBERJACKING"))
        val knownSkills = StubSkillLookup(setOf("LUMBERJACKING"))

        ResourceSpawnsValidator(WorldDefinitionProperties(), itemsWithGoodSkill, knownSkills).validate()
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
            ResourceSpawnsValidator(world, items, EmptySkillLookup).validate()
        }
        assertTrue(ex.message?.contains("spawn-chance") == true)
    }

    private class StubItemLookup(
        ids: Set<String>,
        gatheringSkillFor: Map<String, String> = emptyMap(),
    ) : ItemLookup {
        private val byId = ids.associateWith { id ->
            Item(
                id = ItemId(id),
                displayName = id,
                description = "",
                category = ItemCategory.RESOURCE,
                weightPerUnit = 100,
                maxStack = 100,
                gatheringSkill = gatheringSkillFor[id],
            )
        }
        override fun byId(id: ItemId): Item? = byId[id.value]
        override fun all(): List<Item> = byId.values.toList()
    }

    private class StubSkillLookup(ids: Set<String>) : SkillLookup {
        private val byId = ids.associate { id ->
            SkillId(id) to Skill(
                id = SkillId(id),
                displayName = id,
                description = "stub",
                category = dev.gvart.genesara.player.SkillCategory.SURVIVAL,
            )
        }
        override fun byId(id: SkillId): Skill? = byId[id]
        override fun all(): List<Skill> = byId.values.toList()
    }

    /** Validator only consults [SkillLookup] when an item declares a `gathering-skill`; the test items don't, so an empty lookup is sufficient. */
    private object EmptySkillLookup : SkillLookup {
        override fun byId(id: SkillId): Skill? = null
        override fun all(): List<Skill> = emptyList()
    }
}
