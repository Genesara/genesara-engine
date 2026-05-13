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

            assertTrue(all.size >= 2, "expected at least WHEAT + MEDICINAL_HERB; got ${all.size}")

            val wheat = assertNotNull(lookup.byId(CropId("WHEAT")))
            assertEquals(ItemId("WHEAT_SEED"), wheat.seedItem)
            assertEquals(ItemId("WHEAT"), wheat.outputItem)
            assertEquals(60L, wheat.ticksToRipe)
            assertEquals(4, wheat.baseYield)
            assertEquals(30L, wheat.neglectWindowTicks)
            assertTrue(Terrain.PLAINS in wheat.requiredTerrain)
            assertEquals(SkillId("FARMING"), wheat.farmingSkill)

            val medicinalHerb = assertNotNull(lookup.byId(CropId("MEDICINAL_HERB")))
            assertEquals(ItemId("HERB"), medicinalHerb.outputItem)
            assertEquals(25, medicinalHerb.requiredFarmingLevel)
            assertTrue(medicinalHerb.maxLuckBonus > 0)

            for (crop in all) {
                assertTrue(crop.ticksToRipe > 0, "${crop.id} ticks-to-ripe must be > 0")
                assertTrue(crop.baseYield > 0, "${crop.id} base-yield must be > 0")
                assertTrue(crop.requiredTerrain.isNotEmpty(), "${crop.id} required-terrain must be non-empty")
            }
        }
    }
}
