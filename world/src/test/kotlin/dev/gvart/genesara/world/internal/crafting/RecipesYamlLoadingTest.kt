package dev.gvart.genesara.world.internal.crafting

import dev.gvart.genesara.player.Skill
import dev.gvart.genesara.player.SkillCategory
import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.player.SkillLookup
import dev.gvart.genesara.world.internal.balance.ItemBalanceConfiguration
import dev.gvart.genesara.world.internal.balance.ItemDefinitionProperties
import dev.gvart.genesara.world.internal.balance.ItemLookupImpl
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

    private class StubSkillLookup(private val ids: Set<SkillId>) : SkillLookup {
        override fun byId(id: SkillId): Skill? =
            if (id in ids) Skill(id, id.value, "", SkillCategory.CRAFTING) else null
        override fun all(): List<Skill> = ids.map { Skill(it, it.value, "", SkillCategory.CRAFTING) }
    }
}
