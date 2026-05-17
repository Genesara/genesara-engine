package dev.gvart.genesara.world.internal.crafting

import dev.gvart.genesara.player.Perk
import dev.gvart.genesara.player.PerkChoice
import dev.gvart.genesara.player.PerkEffect
import dev.gvart.genesara.player.PerkId
import dev.gvart.genesara.player.PerkLookup
import dev.gvart.genesara.player.ScalingEffect
import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.world.BuildingCategoryHint
import dev.gvart.genesara.world.Item
import dev.gvart.genesara.world.ItemCategory
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.ItemLookup
import dev.gvart.genesara.world.Recipe
import dev.gvart.genesara.world.RecipeId
import dev.gvart.genesara.world.RecipeLookup
import dev.gvart.genesara.world.RecipeOutput
import dev.gvart.genesara.world.RecipeUnlockMode
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RecipeUnlockReferentialValidatorTest {

    private val knownPerk = PerkId("SMITHING_FORGE_MASTER")
    private val knownItem = ItemId("RECIPE_SCROLL_ALCHEMY")

    @Test
    fun `open recipes pass validation regardless of perk and item catalogs`() {
        val v = RecipeUnlockReferentialValidator(
            recipes = recipesOf(recipe("X", RecipeUnlockMode.Open)),
            perks = StubPerks(emptyList()),
            items = StubItems(emptyMap()),
        )
        v.validate()
    }

    @Test
    fun `class-perk recipe passes when the perk is in the catalog`() {
        val v = RecipeUnlockReferentialValidator(
            recipes = recipesOf(recipe("RX", RecipeUnlockMode.ClassPerk(knownPerk))),
            perks = StubPerks(listOf(perkFor(knownPerk))),
            items = StubItems(emptyMap()),
        )
        v.validate()
    }

    @Test
    fun `item-learned recipe passes when the item is in the catalog`() {
        val v = RecipeUnlockReferentialValidator(
            recipes = recipesOf(recipe("RX", RecipeUnlockMode.ItemLearned(knownItem))),
            perks = StubPerks(emptyList()),
            items = StubItems(mapOf(knownItem to itemFor(knownItem))),
        )
        v.validate()
    }

    @Test
    fun `class-perk recipe fails app boot when the perk is missing from the catalog`() {
        val v = RecipeUnlockReferentialValidator(
            recipes = recipesOf(recipe("RX", RecipeUnlockMode.ClassPerk(PerkId("PHANTOM")))),
            perks = StubPerks(emptyList()),
            items = StubItems(emptyMap()),
        )
        val ex = assertFailsWith<IllegalArgumentException> { v.validate() }
        assertTrue("RX" in ex.message!! && "PHANTOM" in ex.message!!)
    }

    @Test
    fun `item-learned recipe fails app boot when the item is missing from the catalog`() {
        val v = RecipeUnlockReferentialValidator(
            recipes = recipesOf(recipe("RX", RecipeUnlockMode.ItemLearned(ItemId("PHANTOM_SCROLL")))),
            perks = StubPerks(emptyList()),
            items = StubItems(emptyMap()),
        )
        val ex = assertFailsWith<IllegalArgumentException> { v.validate() }
        assertTrue("RX" in ex.message!! && "PHANTOM_SCROLL" in ex.message!!)
    }

    private fun recipe(id: String, unlock: RecipeUnlockMode): Recipe = Recipe(
        id = RecipeId(id),
        output = RecipeOutput(ItemId("OUT"), quantity = 1),
        inputs = emptyMap(),
        requiredStation = BuildingCategoryHint.CRAFTING_STATION_WOOD,
        requiredSkill = SkillId("CARPENTRY"),
        requiredSkillLevel = 0,
        staminaCost = 1,
        unlockMode = unlock,
    )

    private fun recipesOf(vararg r: Recipe): RecipeLookup = object : RecipeLookup {
        override fun byId(id: RecipeId): Recipe? = r.firstOrNull { it.id == id }
        override fun all(): List<Recipe> = r.toList()
    }

    private fun perkFor(id: PerkId): Perk = Perk(
        id = id,
        skill = SkillId("SMITHING"),
        milestoneLevel = 50,
        displayName = id.value,
        description = "",
        effect = PerkEffect.Modifier(target = ScalingEffect.CRAFT_QUALITY_BONUS, multiplier = 1.0),
    )

    private fun itemFor(id: ItemId): Item = Item(
        id = id,
        displayName = id.value,
        description = "",
        category = ItemCategory.RESOURCE,
        weightPerUnit = 1,
        maxStack = 1,
    )

    private class StubPerks(private val all: List<Perk>) : PerkLookup {
        override fun byId(id: PerkId): Perk? = all.firstOrNull { it.id == id }
        override fun choicesAt(skill: SkillId, milestoneLevel: Int): PerkChoice? = null
        override fun choicesFor(skill: SkillId): List<PerkChoice> = emptyList()
        override fun all(): List<Perk> = all
    }

    private class StubItems(private val byId: Map<ItemId, Item>) : ItemLookup {
        override fun byId(id: ItemId): Item? = byId[id]
        override fun all(): List<Item> = byId.values.toList()
    }
}
