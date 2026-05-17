package dev.gvart.genesara.world.internal.crafting

import dev.gvart.genesara.player.Skill
import dev.gvart.genesara.player.SkillCategory
import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.player.SkillLookup
import dev.gvart.genesara.world.BuildingCategoryHint
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.internal.balance.ItemBalanceConfiguration
import dev.gvart.genesara.world.internal.balance.ItemDefinitionProperties
import dev.gvart.genesara.world.internal.balance.ItemLookupImpl
import dev.gvart.genesara.world.internal.balance.RecipeCatalogValidator
import dev.gvart.genesara.world.internal.buildings.BuildingDefinitionProperties
import dev.gvart.genesara.world.internal.buildings.BuildingsCatalog
import dev.gvart.genesara.world.internal.buildings.BuildingsConfiguration
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.ConfigurationPropertiesBindingPostProcessor
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RecipesYamlLoadingTest {

    @Test
    fun `every shipped recipes_yaml file binds into the catalog and preserves recipe shape`() {
        AnnotationConfigApplicationContext().use { ctx ->
            ConfigurationPropertiesBindingPostProcessor.register(ctx)
            ctx.register(RecipeBalanceConfiguration::class.java)
            ctx.refresh()

            val props = ctx.getBean(RecipeDefinitionProperties::class.java)
            val lookup = RecipeLookupImpl(props)
            val all = lookup.all()

            assertTrue(all.size >= 25, "expected the seeded catalog (~30 recipes); got ${all.size}")

            val byId = all.associateBy { it.id.value }
            val ironSword = assertNotNull(byId["IRON_SWORD_BASIC"])
            assertNotNull(ironSword.inputs[dev.gvart.genesara.world.ItemId("IRON_INGOT")])
            val potion = assertNotNull(byId["HEALTH_POTION_BASIC"])
            assertNotNull(potion.inputs[dev.gvart.genesara.world.ItemId("HEALING_SALVE")])
            val ingot = assertNotNull(byId["IRON_INGOT_BASIC"])
            assertNotNull(ingot.inputs[dev.gvart.genesara.world.ItemId("ORE")])

            for (recipe in all) {
                assertTrue(recipe.staminaCost > 0, "${recipe.id} stamina-cost must be > 0")
                assertTrue(recipe.output.quantity > 0, "${recipe.id} output quantity must be > 0")
            }
        }
    }

    @Test
    fun `shipped catalog passes RecipeCatalogValidator against the live items and buildings catalogs`() {
        AnnotationConfigApplicationContext().use { ctx ->
            ConfigurationPropertiesBindingPostProcessor.register(ctx)
            ctx.register(
                RecipeBalanceConfiguration::class.java,
                ItemBalanceConfiguration::class.java,
                BuildingsConfiguration::class.java,
            )
            ctx.refresh()

            val recipes = RecipeLookupImpl(ctx.getBean(RecipeDefinitionProperties::class.java))
            val items = ItemLookupImpl(ctx.getBean(ItemDefinitionProperties::class.java))
            val buildings = BuildingsCatalog(ctx.getBean(BuildingDefinitionProperties::class.java))
            // Player's SkillLookupImpl is internal-scoped to its own module; for this
            // smoke test we hand-roll a SkillLookup over the skill ids the shipped
            // catalog actually references. Cheaper than a SpringBootTest spinning the
            // player module's @Configuration into the world test classpath.
            val referencedSkills = recipes.all().map { it.requiredSkill }.toSet()
            val skills = StubSkillLookup(referencedSkills)

            RecipeCatalogValidator(recipes, items, skills, buildings).validate()
        }
    }

    @Test
    fun `leather, cloth and jewelry recipes route to LEATHERWORKING, TAILORING and JEWELRYCRAFTING`() {
        AnnotationConfigApplicationContext().use { ctx ->
            ConfigurationPropertiesBindingPostProcessor.register(ctx)
            ctx.register(RecipeBalanceConfiguration::class.java)
            ctx.refresh()

            val byId = RecipeLookupImpl(ctx.getBean(RecipeDefinitionProperties::class.java))
                .all()
                .associateBy { it.id.value }

            fun requireSkillOf(id: String) =
                assertNotNull(byId[id], "recipe $id missing from catalog").requiredSkill

            assertEquals(SkillId("LEATHERWORKING"), requireSkillOf("LEATHER_HELMET_BASIC"))
            assertEquals(SkillId("LEATHERWORKING"), requireSkillOf("LEATHER_TUNIC_BASIC"))
            assertEquals(SkillId("LEATHERWORKING"), requireSkillOf("LEATHER_PANTS_BASIC"))
            assertEquals(SkillId("LEATHERWORKING"), requireSkillOf("LEATHER_BOOTS_BASIC"))
            assertEquals(SkillId("LEATHERWORKING"), requireSkillOf("LEATHER_GLOVES_BASIC"))

            assertEquals(SkillId("TAILORING"), requireSkillOf("CLOTH_HOOD_BASIC"))
            assertEquals(SkillId("TAILORING"), requireSkillOf("CLOTH_ROBE_BASIC"))

            assertEquals(SkillId("JEWELRYCRAFTING"), requireSkillOf("GEM_AMULET_BASIC"))
            assertEquals(SkillId("JEWELRYCRAFTING"), requireSkillOf("IRON_RING_BASIC"))
            assertEquals(SkillId("JEWELRYCRAFTING"), requireSkillOf("GEM_RING_BASIC"))
            assertEquals(SkillId("JEWELRYCRAFTING"), requireSkillOf("LEATHER_BRACELET_BASIC"))
            assertEquals(SkillId("JEWELRYCRAFTING"), requireSkillOf("IRON_BRACELET_BASIC"))
        }
    }

    @Test
    fun `fauna-expansion recipes (Slice 3) all load with the expected inputs, station, skill, level, stamina`() {
        AnnotationConfigApplicationContext().use { ctx ->
            ConfigurationPropertiesBindingPostProcessor.register(ctx)
            ctx.register(RecipeBalanceConfiguration::class.java)
            ctx.refresh()

            val byId = RecipeLookupImpl(ctx.getBean(RecipeDefinitionProperties::class.java))
                .all()
                .associateBy { it.id.value }

            data class Expected(
                val outputItem: String,
                val outputQty: Int,
                val inputs: Map<String, Int>,
                val station: BuildingCategoryHint,
                val skill: String,
                val level: Int,
                val stamina: Int,
            )

            val expected = mapOf(
                // cooking
                "ROAST_MEAT" to Expected("ROAST_MEAT", 1, mapOf("MEAT" to 1), BuildingCategoryHint.COOKING, "COOKING", 0, 4),
                "MEAT_STEW" to Expected("MEAT_STEW", 1, mapOf("MEAT" to 1, "POTATO" to 1, "HERB" to 1), BuildingCategoryHint.COOKING, "COOKING", 20, 9),
                "GAME_PIE" to Expected("GAME_PIE", 1, mapOf("MEAT" to 2, "WHEAT" to 2), BuildingCategoryHint.COOKING, "COOKING", 40, 12),
                "HEARTY_BROTH" to Expected("HEARTY_BROTH", 1, mapOf("BONE" to 1, "HERB" to 1), BuildingCategoryHint.COOKING, "COOKING", 10, 7),
                "SMOKED_MEAT" to Expected("SMOKED_MEAT", 1, mapOf("MEAT" to 2), BuildingCategoryHint.CRAFTING_STATION_PRESERVE, "COOKING", 15, 8),
                "SMOKED_FISH" to Expected("SMOKED_FISH", 1, mapOf("FISH" to 2), BuildingCategoryHint.CRAFTING_STATION_PRESERVE, "COOKING", 15, 8),
                "JERKY" to Expected("JERKY", 1, mapOf("MEAT" to 1, "SALT" to 1), BuildingCategoryHint.CRAFTING_STATION_PRESERVE, "COOKING", 25, 10),
                "VENISON_ROAST" to Expected("VENISON_ROAST", 1, mapOf("MEAT" to 3, "HERB" to 2, "SALT" to 1), BuildingCategoryHint.COOKING, "COOKING", 60, 14),
                "BONE_MARROW_SOUP" to Expected("BONE_MARROW_SOUP", 1, mapOf("BONE" to 2, "HERB" to 1), BuildingCategoryHint.COOKING, "COOKING", 30, 10),
                // brewing
                "ALE" to Expected("ALE", 1, mapOf("WHEAT" to 3), BuildingCategoryHint.CRAFTING_STATION_BREW, "BREWING", 0, 8),
                "BERRY_WINE" to Expected("BERRY_WINE", 1, mapOf("BERRY" to 5), BuildingCategoryHint.CRAFTING_STATION_BREW, "BREWING", 20, 10),
                "HERBAL_TONIC" to Expected("HERBAL_TONIC", 1, mapOf("HERB" to 3), BuildingCategoryHint.CRAFTING_STATION_BREW, "BREWING", 10, 7),
                "MEAD" to Expected("MEAD", 1, mapOf("HONEY" to 2), BuildingCategoryHint.CRAFTING_STATION_BREW, "BREWING", 30, 11),
                "MUSHROOM_LIQUOR" to Expected("MUSHROOM_LIQUOR", 1, mapOf("MUSHROOM" to 4), BuildingCategoryHint.CRAFTING_STATION_BREW, "BREWING", 50, 13),
                // weapons
                "BONE_DAGGER_BASIC" to Expected("BONE_DAGGER", 1, mapOf("BONE" to 3, "WOOD" to 1), BuildingCategoryHint.CRAFTING_STATION_WOOD, "CARPENTRY", 0, 10),
                "FANG_DAGGER_BASIC" to Expected("FANG_DAGGER", 1, mapOf("FANG" to 2, "LEATHER" to 1), BuildingCategoryHint.CRAFTING_STATION_METAL, "SMITHING", 20, 12),
                "HORN_CLUB_BASIC" to Expected("HORN_CLUB", 1, mapOf("HORN" to 1, "WOOD" to 2), BuildingCategoryHint.CRAFTING_STATION_WOOD, "CARPENTRY", 5, 11),
                "BONE_ARROW_BATCH" to Expected("BONE_ARROW", 5, mapOf("BONE" to 1, "FEATHER" to 1, "WOOD" to 1), BuildingCategoryHint.CRAFTING_STATION_WOOD, "CARPENTRY", 0, 8),
                // armor
                "FUR_CLOAK_BASIC" to Expected("FUR_CLOAK", 1, mapOf("FUR" to 4, "CLOTH" to 2), BuildingCategoryHint.CRAFTING_STATION_WOOD, "TAILORING", 20, 14),
                "FUR_HOOD_BASIC" to Expected("FUR_HOOD", 1, mapOf("FUR" to 2), BuildingCategoryHint.CRAFTING_STATION_WOOD, "TAILORING", 0, 10),
                "HORN_HELMET_BASIC" to Expected("HORN_HELMET", 1, mapOf("HORN" to 2, "LEATHER" to 1), BuildingCategoryHint.CRAFTING_STATION_WOOD, "LEATHERWORKING", 30, 12),
                "SCALE_VEST_BASIC" to Expected("SCALE_VEST", 1, mapOf("SCALE" to 6, "LEATHER" to 2), BuildingCategoryHint.CRAFTING_STATION_METAL, "SMITHING", 40, 16),
                "CHITIN_SHIELD_BASIC" to Expected("CHITIN_SHIELD", 1, mapOf("CHITIN" to 4, "WOOD" to 1), BuildingCategoryHint.CRAFTING_STATION_WOOD, "CARPENTRY", 30, 13),
                "CHITIN_GAUNTLETS_BASIC" to Expected("CHITIN_GAUNTLETS", 1, mapOf("CHITIN" to 3, "LEATHER" to 1), BuildingCategoryHint.CRAFTING_STATION_WOOD, "LEATHERWORKING", 30, 11),
                // jewelry
                "BONE_AMULET_BASIC" to Expected("BONE_AMULET", 1, mapOf("BONE" to 2), BuildingCategoryHint.CRAFTING_STATION_WOOD, "JEWELRYCRAFTING", 0, 8),
                "FANG_NECKLACE_BASIC" to Expected("FANG_NECKLACE", 1, mapOf("FANG" to 3, "SINEW" to 1), BuildingCategoryHint.CRAFTING_STATION_WOOD, "JEWELRYCRAFTING", 20, 10),
                // intermediates
                "HARDENED_LEATHER_CURE" to Expected("HARDENED_LEATHER", 1, mapOf("HIDE" to 2, "GLAND" to 1), BuildingCategoryHint.CRAFTING_STATION_WOOD, "LEATHERWORKING", 30, 12),
                "BONE_MEAL_GRIND" to Expected("BONE_MEAL", 2, mapOf("BONE" to 1), BuildingCategoryHint.CRAFTING_STATION_WOOD, "CARPENTRY", 0, 6),
                "DRIED_FUR_PROCESS" to Expected("DRIED_FUR", 1, mapOf("FUR" to 1), BuildingCategoryHint.CRAFTING_STATION_WOOD, "LEATHERWORKING", 10, 8),
                "VENOM_EXTRACT_DISTILL" to Expected("VENOM_EXTRACT", 1, mapOf("GLAND" to 2), BuildingCategoryHint.CRAFTING_STATION_POTION, "ALCHEMY", 20, 10),
                "BOWSTRING_WEAVE" to Expected("BOWSTRING", 1, mapOf("SINEW" to 2), BuildingCategoryHint.CRAFTING_STATION_WOOD, "LEATHERWORKING", 0, 6),
                // consumables
                "POISON_VIAL_BREW" to Expected("POISON_VIAL", 1, mapOf("VENOM_EXTRACT" to 2), BuildingCategoryHint.CRAFTING_STATION_POTION, "ALCHEMY", 30, 11),
                "ANTIDOTE_BREW" to Expected("ANTIDOTE", 1, mapOf("GLAND" to 1, "HERB" to 2), BuildingCategoryHint.CRAFTING_STATION_POTION, "ALCHEMY", 20, 10),
                "STAMINA_TONIC_BREW" to Expected("STAMINA_TONIC", 1, mapOf("HONEY" to 1, "HERB" to 2), BuildingCategoryHint.CRAFTING_STATION_POTION, "ALCHEMY", 10, 8),
            )

            assertEquals(34, expected.size, "expected exactly 34 new Slice-3 recipes")

            for ((id, exp) in expected) {
                val recipe = assertNotNull(byId[id], "missing recipe $id")
                assertEquals(ItemId(exp.outputItem), recipe.output.item, "$id output item")
                assertEquals(exp.outputQty, recipe.output.quantity, "$id output qty")
                assertEquals(
                    exp.inputs.mapKeys { ItemId(it.key) },
                    recipe.inputs,
                    "$id inputs",
                )
                assertEquals(exp.station, recipe.requiredStation, "$id station")
                assertEquals(SkillId(exp.skill), recipe.requiredSkill, "$id skill")
                assertEquals(exp.level, recipe.requiredSkillLevel, "$id level")
                assertEquals(exp.stamina, recipe.staminaCost, "$id stamina")
            }
        }
    }

    @Test
    fun `every Slice-3 recipe output resolves against the live items catalog`() {
        AnnotationConfigApplicationContext().use { ctx ->
            ConfigurationPropertiesBindingPostProcessor.register(ctx)
            ctx.register(
                RecipeBalanceConfiguration::class.java,
                ItemBalanceConfiguration::class.java,
            )
            ctx.refresh()

            val recipes = RecipeLookupImpl(ctx.getBean(RecipeDefinitionProperties::class.java)).all()
            val items = ItemLookupImpl(ctx.getBean(ItemDefinitionProperties::class.java))

            val slice3Ids = setOf(
                "ROAST_MEAT", "MEAT_STEW", "GAME_PIE", "HEARTY_BROTH", "SMOKED_MEAT",
                "SMOKED_FISH", "JERKY", "VENISON_ROAST", "BONE_MARROW_SOUP",
                "ALE", "BERRY_WINE", "HERBAL_TONIC", "MEAD", "MUSHROOM_LIQUOR",
                "BONE_DAGGER_BASIC", "FANG_DAGGER_BASIC", "HORN_CLUB_BASIC", "BONE_ARROW_BATCH",
                "FUR_CLOAK_BASIC", "FUR_HOOD_BASIC", "HORN_HELMET_BASIC", "SCALE_VEST_BASIC",
                "CHITIN_SHIELD_BASIC", "CHITIN_GAUNTLETS_BASIC",
                "BONE_AMULET_BASIC", "FANG_NECKLACE_BASIC",
                "HARDENED_LEATHER_CURE", "BONE_MEAL_GRIND", "DRIED_FUR_PROCESS",
                "VENOM_EXTRACT_DISTILL", "BOWSTRING_WEAVE",
                "POISON_VIAL_BREW", "ANTIDOTE_BREW", "STAMINA_TONIC_BREW",
            )

            for (recipe in recipes.filter { it.id.value in slice3Ids }) {
                assertNotNull(
                    items.byId(recipe.output.item),
                    "recipe ${recipe.id.value} output ${recipe.output.item.value} missing from items catalog",
                )
                for (input in recipe.inputs.keys) {
                    assertNotNull(
                        items.byId(input),
                        "recipe ${recipe.id.value} input ${input.value} missing from items catalog",
                    )
                }
            }
        }
    }

    private class StubSkillLookup(private val ids: Set<SkillId>) : SkillLookup {
        override fun byId(id: SkillId): Skill? =
            if (id in ids) Skill(id, id.value, "", SkillCategory.CRAFTING) else null
        override fun all(): List<Skill> = ids.map { Skill(it, it.value, "", SkillCategory.CRAFTING) }
    }
}
