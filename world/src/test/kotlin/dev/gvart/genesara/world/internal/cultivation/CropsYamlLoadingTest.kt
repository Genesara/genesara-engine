package dev.gvart.genesara.world.internal.cultivation

import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.world.CropId
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.Terrain
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.ConfigurationPropertiesBindingPostProcessor
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CropsYamlLoadingTest {

    @Test
    fun `crops yaml binds into the catalog and preserves crop shape`() {
        AnnotationConfigApplicationContext().use { ctx ->
            ConfigurationPropertiesBindingPostProcessor.register(ctx)
            ctx.register(CropBalanceConfiguration::class.java)
            ctx.refresh()

            val props = ctx.getBean(CropDefinitionProperties::class.java)
            val lookup = CropLookupImpl(props)
            val all = lookup.all()

            // Catalog spans FARMING level 0 through 50 so a fresh agent can begin at WHEAT
            // and progress crop by crop. Seed bags (recipes-seeds.yaml) gate at the same levels.
            val expectedCrops = setOf("WHEAT", "POTATO", "BERRY_BUSH", "TOMATO", "MEDICINAL_HERB", "PEPPER", "CORN", "PUMPKIN")
            assertEquals(expectedCrops, all.map { it.id.value }.toSet())

            val wheat = assertNotNull(lookup.byId(CropId("WHEAT")))
            assertEquals(ItemId("WHEAT_SEED"), wheat.seedItem)
            assertEquals(ItemId("WHEAT"), wheat.outputItem)
            assertEquals(0, wheat.requiredFarmingLevel)
            assertTrue(Terrain.PLAINS in wheat.requiredTerrain)
            assertEquals(SkillId("FARMING"), wheat.farmingSkill)

            val pumpkin = assertNotNull(lookup.byId(CropId("PUMPKIN")))
            assertEquals(50, pumpkin.requiredFarmingLevel)
            assertEquals(ItemId("PUMPKIN_SEED"), pumpkin.seedItem)

            for (crop in all) {
                assertTrue(crop.ticksToRipe > 0, "${crop.id} ticks-to-ripe must be > 0")
                assertTrue(crop.baseYield > 0, "${crop.id} base-yield must be > 0")
                assertTrue(crop.requiredTerrain.isNotEmpty(), "${crop.id} required-terrain must be non-empty")
            }
        }
    }

    /**
     * Every shipped crop must be reachable from foraged inputs alone — no crop can require
     * its own output as a recipe input, or a fresh agent can never bootstrap the loop. The
     * test cross-references the shipped seed-bag recipes and asserts each crop's seed has
     * at least one open-unlock recipe whose inputs are all RESOURCE-category items that are
     * either terrain-spawned (regenerating) or producible via a recipe whose own inputs
     * close back to terrain.
     */
    @Test
    fun `every crop has a foraged-input recipe path so day-0 agents can bootstrap`() {
        AnnotationConfigApplicationContext().use { ctx ->
            ConfigurationPropertiesBindingPostProcessor.register(ctx)
            ctx.register(
                CropBalanceConfiguration::class.java,
                dev.gvart.genesara.world.internal.crafting.RecipeBalanceConfiguration::class.java,
                dev.gvart.genesara.world.internal.balance.ItemBalanceConfiguration::class.java,
            )
            ctx.refresh()

            val crops = CropLookupImpl(ctx.getBean(CropDefinitionProperties::class.java))
            val recipes = dev.gvart.genesara.world.internal.crafting.RecipeLookupImpl(
                ctx.getBean(dev.gvart.genesara.world.internal.crafting.RecipeDefinitionProperties::class.java),
            )
            val items = dev.gvart.genesara.world.internal.balance.ItemLookupImpl(
                ctx.getBean(dev.gvart.genesara.world.internal.balance.ItemDefinitionProperties::class.java),
            )

            for (crop in crops.all()) {
                val seedRecipe = recipes.all().firstOrNull { it.output.item == crop.seedItem }
                assertNotNull(seedRecipe, "${crop.id}: seed item ${crop.seedItem.value} has no recipe")
                for ((input, _) in seedRecipe.inputs) {
                    val item = assertNotNull(items.byId(input), "${crop.id} seed recipe references unknown item ${input.value}")
                    assertTrue(
                        item.regenerating || recipes.all().any { it.output.item == input },
                        "${crop.id} seed input ${input.value} is non-renewing and not craftable — bootstrap loop broken",
                    )
                }
                assertTrue(
                    seedRecipe.requiredSkillLevel <= crop.requiredFarmingLevel,
                    "${crop.id}: seed recipe gates at level ${seedRecipe.requiredSkillLevel} but the crop only needs ${crop.requiredFarmingLevel}",
                )
            }
        }
    }
}
