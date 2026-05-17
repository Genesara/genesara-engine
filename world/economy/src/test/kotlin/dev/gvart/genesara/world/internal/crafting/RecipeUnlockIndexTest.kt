package dev.gvart.genesara.world.internal.crafting

import dev.gvart.genesara.player.PerkId
import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.world.BuildingCategoryHint
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.Recipe
import dev.gvart.genesara.world.RecipeId
import dev.gvart.genesara.world.RecipeLookup
import dev.gvart.genesara.world.RecipeOutput
import dev.gvart.genesara.world.RecipeUnlockMode
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class RecipeUnlockIndexTest {

    @Test
    fun `byPerkId groups every class-perk recipe under its taught-by perk`() {
        val perk = PerkId("SMITHING_FORGE_MASTER")
        val a = recipe("LEGENDARY_BLADE", RecipeUnlockMode.ClassPerk(perk))
        val b = recipe("LEGENDARY_HAMMER", RecipeUnlockMode.ClassPerk(perk))
        val c = recipe("PLAIN_WOOD", RecipeUnlockMode.Open)
        val index = RecipeUnlockIndex(StubRecipes(listOf(a, b, c)))

        assertEquals(listOf(a.id, b.id), index.byPerkId(perk))
    }

    @Test
    fun `byLearningItem groups every item-learned recipe under its scroll`() {
        val scroll = ItemId("RECIPE_SCROLL_ALCHEMY")
        val a = recipe("GREATER_POTION", RecipeUnlockMode.ItemLearned(scroll))
        val b = recipe("BASIC_POTION", RecipeUnlockMode.Open)
        val index = RecipeUnlockIndex(StubRecipes(listOf(a, b)))

        assertEquals(listOf(a.id), index.byLearningItem(scroll))
    }

    @Test
    fun `lookup returns empty for unmapped perk and item`() {
        val index = RecipeUnlockIndex(StubRecipes(listOf(recipe("ANYTHING", RecipeUnlockMode.Open))))

        assertEquals(emptyList(), index.byPerkId(PerkId("UNKNOWN")))
        assertEquals(emptyList(), index.byLearningItem(ItemId("UNKNOWN")))
    }

    private fun recipe(id: String, unlock: RecipeUnlockMode): Recipe = Recipe(
        id = RecipeId(id),
        output = RecipeOutput(ItemId("PLACEHOLDER"), quantity = 1),
        inputs = emptyMap(),
        requiredStation = BuildingCategoryHint.CRAFTING_STATION_WOOD,
        requiredSkill = SkillId("CARPENTRY"),
        requiredSkillLevel = 0,
        staminaCost = 1,
        unlockMode = unlock,
    )

    private class StubRecipes(private val all: List<Recipe>) : RecipeLookup {
        override fun byId(id: RecipeId): Recipe? = all.firstOrNull { it.id == id }
        override fun all(): List<Recipe> = all
    }
}
