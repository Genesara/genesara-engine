package dev.gvart.genesara.world.internal.crafting

import dev.gvart.genesara.player.Skill
import dev.gvart.genesara.player.SkillCategory
import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.player.SkillLookup
import dev.gvart.genesara.world.BuildingCategoryHint
import dev.gvart.genesara.world.Item
import dev.gvart.genesara.world.ItemCategory
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.ItemLookup
import dev.gvart.genesara.world.Recipe
import dev.gvart.genesara.world.RecipeId
import dev.gvart.genesara.world.RecipeLookup
import dev.gvart.genesara.world.RecipeOutput
import dev.gvart.genesara.world.internal.buildings.BuildingDefinitionProperties
import dev.gvart.genesara.world.internal.buildings.BuildingProperties
import dev.gvart.genesara.world.internal.buildings.BuildingsCatalog
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertTrue

class RecipeCatalogValidatorTest {

    private val items = StubItemLookup(setOf("ORE", "COAL", "WOOD", "IRON_INGOT", "IRON_SWORD"))
    private val skills = StubSkillLookup(setOf("SMITHING", "CARPENTRY"))
    private val buildings = catalogWithStations(
        setOf(BuildingCategoryHint.CRAFTING_STATION_METAL, BuildingCategoryHint.CRAFTING_STATION_WOOD),
    )

    private val validRecipe = recipeOf(
        id = "IRON_SWORD_BASIC",
        outputItem = "IRON_SWORD",
        inputs = mapOf("IRON_INGOT" to 2, "WOOD" to 1),
        station = BuildingCategoryHint.CRAFTING_STATION_METAL,
        skill = "SMITHING",
    )

    @Test
    fun `accepts a fully-valid catalog`() {
        RecipeCatalogValidator(StubRecipeLookup(listOf(validRecipe)), items, skills, buildings).validate()
    }

    @Test
    fun `rejects when output item is not in the items catalog`() {
        val bad = validRecipe.copy(output = RecipeOutput(ItemId("PHANTOM"), 1))
        val ex = assertThrows<IllegalArgumentException> {
            RecipeCatalogValidator(StubRecipeLookup(listOf(bad)), items, skills, buildings).validate()
        }
        assertTrue(ex.message?.contains("PHANTOM") == true)
    }

    @Test
    fun `rejects when an input item is not in the items catalog`() {
        val bad = validRecipe.copy(inputs = mapOf(ItemId("MOONSTONE") to 1))
        val ex = assertThrows<IllegalArgumentException> {
            RecipeCatalogValidator(StubRecipeLookup(listOf(bad)), items, skills, buildings).validate()
        }
        assertTrue(ex.message?.contains("MOONSTONE") == true)
    }

    @Test
    fun `rejects when required-skill is not in the skill catalog`() {
        val bad = validRecipe.copy(requiredSkill = SkillId("NECROMANCY"))
        val ex = assertThrows<IllegalArgumentException> {
            RecipeCatalogValidator(StubRecipeLookup(listOf(bad)), items, skills, buildings).validate()
        }
        assertTrue(ex.message?.contains("NECROMANCY") == true)
    }

    @Test
    fun `rejects when required-station has no matching building variant`() {
        val bad = validRecipe.copy(requiredStation = BuildingCategoryHint.AGRICULTURE)
        val ex = assertThrows<IllegalArgumentException> {
            RecipeCatalogValidator(StubRecipeLookup(listOf(bad)), items, skills, buildings).validate()
        }
        assertTrue(ex.message?.contains("AGRICULTURE") == true)
    }

    @Test
    fun `rejects non-positive output quantity`() {
        val bad = validRecipe.copy(output = RecipeOutput(ItemId("IRON_SWORD"), 0))
        val ex = assertThrows<IllegalArgumentException> {
            RecipeCatalogValidator(StubRecipeLookup(listOf(bad)), items, skills, buildings).validate()
        }
        assertTrue(ex.message?.contains("output quantity") == true)
    }

    @Test
    fun `rejects non-positive stamina-cost`() {
        val bad = validRecipe.copy(staminaCost = 0)
        val ex = assertThrows<IllegalArgumentException> {
            RecipeCatalogValidator(StubRecipeLookup(listOf(bad)), items, skills, buildings).validate()
        }
        assertTrue(ex.message?.contains("stamina-cost") == true)
    }

    @Test
    fun `rejects negative required-skill-level`() {
        val bad = validRecipe.copy(requiredSkillLevel = -1)
        val ex = assertThrows<IllegalArgumentException> {
            RecipeCatalogValidator(StubRecipeLookup(listOf(bad)), items, skills, buildings).validate()
        }
        assertTrue(ex.message?.contains("required-skill-level") == true)
    }

    @Test
    fun `accumulates problems and reports all`() {
        val mangled = validRecipe.copy(
            output = RecipeOutput(ItemId("BOGUS_OUT"), 1),
            inputs = mapOf(ItemId("BOGUS_IN") to 1),
            requiredSkill = SkillId("BOGUS_SKILL"),
        )
        val ex = assertThrows<IllegalArgumentException> {
            RecipeCatalogValidator(StubRecipeLookup(listOf(mangled)), items, skills, buildings).validate()
        }
        assertTrue(ex.message?.contains("BOGUS_OUT") == true)
        assertTrue(ex.message?.contains("BOGUS_IN") == true)
        assertTrue(ex.message?.contains("BOGUS_SKILL") == true)
    }

    private fun recipeOf(
        id: String,
        outputItem: String,
        inputs: Map<String, Int>,
        station: BuildingCategoryHint,
        skill: String,
        skillLevel: Int = 0,
        stamina: Int = 10,
    ): Recipe = Recipe(
        id = RecipeId(id),
        output = RecipeOutput(ItemId(outputItem), 1),
        inputs = inputs.entries.associate { (k, v) -> ItemId(k) to v },
        requiredStation = station,
        requiredSkill = SkillId(skill),
        requiredSkillLevel = skillLevel,
        staminaCost = stamina,
    )

    private fun catalogWithStations(stations: Set<BuildingCategoryHint>): BuildingsCatalog {
        val catalog = stations.associate { hint ->
            "STATION_${hint.name}" to BuildingProperties(
                requiredSkill = "CARPENTRY",
                totalSteps = 5,
                staminaPerStep = 5,
                hp = 50,
                categoryHint = hint,
                totalMaterials = mapOf("WOOD" to 5),
            )
        }
        // Stations need real BuildingType enum names; cheat by using existing variants
        // that map to the hints we want. Validation only inspects categoryHints.
        val realCatalog = mapOf(
            "FORGE" to BuildingProperties(
                requiredSkill = "CARPENTRY", totalSteps = 5, staminaPerStep = 5, hp = 50,
                categoryHint = BuildingCategoryHint.CRAFTING_STATION_METAL, totalMaterials = mapOf("WOOD" to 5),
            ),
            "WORKBENCH" to BuildingProperties(
                requiredSkill = "CARPENTRY", totalSteps = 5, staminaPerStep = 5, hp = 50,
                categoryHint = BuildingCategoryHint.CRAFTING_STATION_WOOD, totalMaterials = mapOf("WOOD" to 5),
            ),
        )
        return BuildingsCatalog(BuildingDefinitionProperties(catalog = realCatalog.filterValues { it.categoryHint in stations }))
    }

    private class StubItemLookup(private val ids: Set<String>) : ItemLookup {
        override fun byId(id: ItemId): Item? =
            if (id.value in ids) {
                Item(
                    id = id,
                    displayName = id.value,
                    description = "",
                    category = ItemCategory.RESOURCE,
                    weightPerUnit = 100,
                    maxStack = 100,
                )
            } else null
        override fun all(): List<Item> = ids.map {
            Item(ItemId(it), it, "", ItemCategory.RESOURCE, 100, 100)
        }
    }

    private class StubSkillLookup(private val ids: Set<String>) : SkillLookup {
        override fun byId(id: SkillId): Skill? =
            if (id.value in ids) Skill(id, id.value, "", SkillCategory.CRAFTING) else null
        override fun all(): List<Skill> = ids.map { Skill(SkillId(it), it, "", SkillCategory.CRAFTING) }
    }

    private class StubRecipeLookup(private val recipes: List<Recipe>) : RecipeLookup {
        private val byId = recipes.associateBy { it.id }
        override fun byId(id: RecipeId): Recipe? = byId[id]
        override fun all(): List<Recipe> = recipes
    }
}
