package dev.gvart.genesara.world.internal.npc

import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.LootTableCatalog
import dev.gvart.genesara.world.NpcType
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.ConfigurationPropertiesBindingPostProcessor
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LootTablesYamlLoadingTest {

    @Test
    fun `production mob-keyed loot-tables_yaml binds and resolves drops per mob`() {
        AnnotationConfigApplicationContext().use { ctx ->
            ConfigurationPropertiesBindingPostProcessor.register(ctx)
            ctx.register(NpcCatalogConfiguration::class.java)
            ctx.refresh()

            val catalog = ctx.getBean(LootTableCatalog::class.java)

            val wolfDrops = catalog.byMob(NpcType("GRAY_WOLF"))
            assertEquals(1, wolfDrops.size)
            assertEquals(ItemId("HIDE"), wolfDrops.single().item)
            assertEquals(0.9, wolfDrops.single().dropChance)

            val boarDrops = catalog.byMob(NpcType("WILD_BOAR"))
            assertEquals(ItemId("HIDE"), boarDrops.single().item)
            assertEquals(0.8, boarDrops.single().dropChance)

            val deerDrops = catalog.byMob(NpcType("DEER"))
            assertEquals(ItemId("HIDE"), deerDrops.single().item)
            assertEquals(1.0, deerDrops.single().dropChance)

            val ratDrops = catalog.byMob(NpcType("GIANT_RAT"))
            assertEquals(2, ratDrops.size)
            assertTrue(ratDrops.any { it.item == ItemId("FIBER") })
            assertTrue(ratDrops.any { it.item == ItemId("BERRY") })

            // Lock the full mob set so future stale config edits can't silently add phantom entries.
            assertEquals(
                setOf(
                    NpcType("GRAY_WOLF"), NpcType("WILD_BOAR"), NpcType("DEER"), NpcType("GIANT_RAT"),
                    NpcType("BROWN_BEAR"), NpcType("ELK"), NpcType("COUGAR"), NpcType("BLACK_PANTHER"),
                    NpcType("BISON"), NpcType("MOUNTAIN_GOAT"), NpcType("DESERT_JACKAL"), NpcType("RED_FOX"),
                    NpcType("SNOW_HARE"), NpcType("RIVER_OTTER"), NpcType("CAVE_BAT"),
                    NpcType("GIANT_OWL"), NpcType("HAWK"), NpcType("VULTURE"),
                    NpcType("WILD_TURKEY"), NpcType("PHEASANT"),
                    NpcType("SWAMP_PYTHON"), NpcType("MONITOR_LIZARD"), NpcType("BOG_TURTLE"),
                    NpcType("SAND_VIPER"), NpcType("CAVE_SALAMANDER"),
                    NpcType("GIANT_SPIDER"), NpcType("SAND_SCORPION"), NpcType("CENTIPEDE"), NpcType("HIVE_BEE"),
                ),
                catalog.allMobs(),
            )
        }
    }

    @Test
    fun `unknown mob returns empty drop list`() {
        AnnotationConfigApplicationContext().use { ctx ->
            ConfigurationPropertiesBindingPostProcessor.register(ctx)
            ctx.register(NpcCatalogConfiguration::class.java)
            ctx.refresh()

            val catalog = ctx.getBean(LootTableCatalog::class.java)
            assertEquals(emptyList(), catalog.byMob(NpcType("PHANTOM_BEAST")))
        }
    }
}
