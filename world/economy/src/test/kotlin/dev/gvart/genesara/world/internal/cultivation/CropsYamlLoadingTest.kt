package dev.gvart.genesara.world.internal.cultivation

import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.world.CropId
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.Terrain
import dev.gvart.genesara.world.internal.balance.WorldDefinitionBalanceLookup
import dev.gvart.genesara.world.internal.balance.WorldBalanceConfiguration
import dev.gvart.genesara.world.internal.balance.WorldDefinitionProperties
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
            // and progress crop by crop.
            val expectedCrops = setOf("WHEAT", "POTATO", "BERRY_BUSH", "TOMATO", "MEDICINAL_HERB", "PEPPER", "CORN", "PUMPKIN")
            assertEquals(expectedCrops, all.map { it.id.value }.toSet())

            val wheat = assertNotNull(lookup.byId(CropId("WHEAT")))
            assertEquals(ItemId("WHEAT"), wheat.seedItem, "the crop's own output is its seed")
            assertEquals(ItemId("WHEAT"), wheat.outputItem)
            assertEquals(0, wheat.requiredFarmingLevel)
            assertTrue(Terrain.PLAINS in wheat.requiredTerrain)
            assertEquals(SkillId("FARMING"), wheat.farmingSkill)

            val pumpkin = assertNotNull(lookup.byId(CropId("PUMPKIN")))
            assertEquals(50, pumpkin.requiredFarmingLevel)
            assertEquals(ItemId("PUMPKIN"), pumpkin.seedItem)

            for (crop in all) {
                assertTrue(crop.ticksToRipe > 0, "${crop.id} ticks-to-ripe must be > 0")
                assertTrue(crop.baseYield > 0, "${crop.id} base-yield must be > 0")
                assertTrue(crop.requiredTerrain.isNotEmpty(), "${crop.id} required-terrain must be non-empty")
                assertEquals(
                    crop.outputItem, crop.seedItem,
                    "${crop.id}: seed-item must equal output-item — crops are their own seeds",
                )
            }
        }
    }

    /**
     * Bootstrap reachability: every crop's seed-item (== output-item) must spawn somewhere
     * on terrain so a fresh agent can forage a starter unit and plant their first crop.
     * Without this, a crop catalog id is reachable only via admin spawn or barter.
     */
    @Test
    fun `every crop's output spawns on at least one terrain so day-0 agents can bootstrap`() {
        AnnotationConfigApplicationContext().use { ctx ->
            ConfigurationPropertiesBindingPostProcessor.register(ctx)
            ctx.register(
                CropBalanceConfiguration::class.java,
                WorldBalanceConfiguration::class.java,
            )
            ctx.refresh()

            val crops = CropLookupImpl(ctx.getBean(CropDefinitionProperties::class.java))
            val balance = WorldDefinitionBalanceLookup(ctx.getBean(WorldDefinitionProperties::class.java))
            val spawnedItems: Set<ItemId> = Terrain.entries
                .flatMap { balance.resourceSpawnsFor(it).map { rule -> rule.item } }
                .toSet()

            for (crop in crops.all()) {
                assertTrue(
                    crop.seedItem in spawnedItems,
                    "${crop.id}: seed-item ${crop.seedItem.value} is not in any terrain's resource-spawns — agents have no way to bootstrap",
                )
            }
        }
    }
}
