package dev.gvart.genesara.world.environment.internal.npc

import dev.gvart.genesara.world.AggressionProfile
import dev.gvart.genesara.world.Biome
import dev.gvart.genesara.world.DamageType
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.LootTableCatalog
import dev.gvart.genesara.world.NpcCatalog
import dev.gvart.genesara.world.NpcType
import dev.gvart.genesara.world.internal.balance.ItemBalanceConfiguration
import dev.gvart.genesara.world.internal.balance.ItemDefinitionProperties
import dev.gvart.genesara.world.internal.balance.ItemLookupImpl
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.ConfigurationPropertiesBindingPostProcessor
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NpcsYamlLoadingTest {

    @Test
    fun `production npcs_yaml binds 29 fauna with expected combat and spawn fields`() {
        AnnotationConfigApplicationContext().use { ctx ->
            ConfigurationPropertiesBindingPostProcessor.register(ctx)
            ctx.register(NpcCatalogConfiguration::class.java)
            ctx.refresh()

            val catalog = ctx.getBean(NpcCatalog::class.java)

            assertEquals(29, catalog.all().size, "fauna count regression — expected 4 Slice-1 + 25 Slice-2")

            val bear = assertNotNull(catalog.byType(NpcType("BROWN_BEAR")))
            assertEquals(60, bear.hpMax)
            assertEquals(DamageType.SLASH, bear.damageType)
            assertEquals(AggressionProfile.TERRITORIAL, bear.aggressionProfile)
            assertEquals(2, bear.territoryRadius)
            assertEquals(setOf(Biome.FOREST, Biome.MOUNTAIN), bear.spawnBiomes)

            val hawk = assertNotNull(catalog.byType(NpcType("HAWK")))
            assertEquals(2, hawk.range, "ranged birds keep range 2")
            assertEquals(AggressionProfile.HOSTILE, hawk.aggressionProfile)

            val hare = assertNotNull(catalog.byType(NpcType("SNOW_HARE")))
            assertEquals(AggressionProfile.PASSIVE, hare.aggressionProfile)
            assertEquals(0, hare.damage)
            assertEquals(setOf(Biome.TUNDRA), hare.spawnBiomes)

            val vulture = assertNotNull(catalog.byType(NpcType("VULTURE")))
            assertEquals(2, vulture.fleeDistance, "vulture flees 2 nodes on damage")

            val turkey = assertNotNull(catalog.byType(NpcType("WILD_TURKEY")))
            assertEquals(2, turkey.fleeDistance)

            val pheasant = assertNotNull(catalog.byType(NpcType("PHEASANT")))
            assertEquals(2, pheasant.fleeDistance)

            val centipede = assertNotNull(catalog.byType(NpcType("CENTIPEDE")))
            assertTrue(Biome.RUINS in centipede.spawnBiomes, "centipede must spawn in RUINS")
        }
    }

    @Test
    fun `every fauna lists at least one spawn-biome and a positive spawn-weight`() {
        AnnotationConfigApplicationContext().use { ctx ->
            ConfigurationPropertiesBindingPostProcessor.register(ctx)
            ctx.register(NpcCatalogConfiguration::class.java)
            ctx.refresh()

            val catalog = ctx.getBean(NpcCatalog::class.java)
            for (def in catalog.all()) {
                assertTrue(def.spawnBiomes.isNotEmpty(), "${def.type} must declare at least one spawn biome")
                assertTrue(def.spawnWeight >= 1, "${def.type} spawn-weight must be >= 1")
            }
        }
    }

    @Test
    fun `every loot-table drop references an item that exists in items_yaml`() {
        AnnotationConfigApplicationContext().use { ctx ->
            ConfigurationPropertiesBindingPostProcessor.register(ctx)
            ctx.register(NpcCatalogConfiguration::class.java)
            ctx.register(ItemBalanceConfiguration::class.java)
            ctx.refresh()

            val lootCatalog = ctx.getBean(LootTableCatalog::class.java)
            val items = ItemLookupImpl(ctx.getBean(ItemDefinitionProperties::class.java))

            for (mob in lootCatalog.allMobs()) {
                for (drop in lootCatalog.byMob(mob)) {
                    val resolved = items.byId(drop.item)
                    assertNotNull(resolved, "$mob drops unresolved item ${drop.item.value} — items.yaml is missing it")
                }
            }
        }
    }
}
