package dev.gvart.genesara.world.internal.equipment

import dev.gvart.genesara.world.EquipmentSetId
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.internal.balance.ItemBalanceConfiguration
import dev.gvart.genesara.world.internal.balance.ItemDefinitionProperties
import dev.gvart.genesara.world.internal.balance.ItemLookupImpl
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.ConfigurationPropertiesBindingPostProcessor
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EquipmentSetsYamlLoadingTest {

    @Test
    fun `shipped equipment-sets_yaml binds into the catalog`() {
        AnnotationConfigApplicationContext().use { ctx ->
            ConfigurationPropertiesBindingPostProcessor.register(ctx)
            ctx.register(EquipmentSetConfiguration::class.java)
            ctx.refresh()

            val props = ctx.getBean(EquipmentSetDefinitionProperties::class.java)
            val lookup = EquipmentSetLookupImpl(props)

            val iron = assertNotNull(lookup.byId(EquipmentSetId("IRON")))
            assertEquals(5, iron.pieces.size)
            assertTrue(ItemId("IRON_CHESTPLATE") in iron.pieces)
            // Threshold tiers 2, 3, 5 are defined; below-2 yields nothing.
            assertTrue(iron.activeBonuses(1).isEmpty())
            assertTrue(iron.activeBonuses(2).isNotEmpty())
            assertTrue(iron.activeBonuses(5).size >= 4, "5-piece tier stacks 2/3/5 bonuses additively")
        }
    }

    @Test
    fun `shipped IRON set passes the referential validator against the live items catalog`() {
        AnnotationConfigApplicationContext().use { ctx ->
            ConfigurationPropertiesBindingPostProcessor.register(ctx)
            ctx.register(
                EquipmentSetConfiguration::class.java,
                ItemBalanceConfiguration::class.java,
            )
            ctx.refresh()

            val sets = EquipmentSetLookupImpl(ctx.getBean(EquipmentSetDefinitionProperties::class.java))
            val items = ItemLookupImpl(ctx.getBean(ItemDefinitionProperties::class.java))

            EquipmentSetReferentialValidator(sets, items).validate()
        }
    }

    @Test
    fun `shipped equipment items bind their bonuses lists`() {
        AnnotationConfigApplicationContext().use { ctx ->
            ConfigurationPropertiesBindingPostProcessor.register(ctx)
            ctx.register(ItemBalanceConfiguration::class.java)
            ctx.refresh()

            val items = ItemLookupImpl(ctx.getBean(ItemDefinitionProperties::class.java))

            val ironChest = assertNotNull(items.byId(ItemId("IRON_CHESTPLATE")))
            assertTrue(ironChest.bonuses.isNotEmpty(), "IRON_CHESTPLATE must carry bonuses")
            assertTrue(
                ironChest.bonuses.any { it is dev.gvart.genesara.world.EquippedBonus.ArmorDef &&
                    it.damageType == dev.gvart.genesara.world.DamageType.SLASH },
                "IRON_CHESTPLATE must carry an ArmorDef(SLASH, _) entry",
            )

            val gemRing = assertNotNull(items.byId(ItemId("GEM_RING")))
            assertTrue(
                gemRing.bonuses.any { it is dev.gvart.genesara.world.EquippedBonus.PassiveBuff &&
                    it.effect == dev.gvart.genesara.player.ScalingEffect.CRIT_CHANCE },
                "GEM_RING must carry a PassiveBuff(CRIT_CHANCE, _) entry",
            )
        }
    }

    @Test
    fun `setsContaining reverse index resolves IRON_CHESTPLATE to the IRON set`() {
        AnnotationConfigApplicationContext().use { ctx ->
            ConfigurationPropertiesBindingPostProcessor.register(ctx)
            ctx.register(EquipmentSetConfiguration::class.java)
            ctx.refresh()

            val lookup = EquipmentSetLookupImpl(ctx.getBean(EquipmentSetDefinitionProperties::class.java))
            val sets = lookup.setsContaining(ItemId("IRON_CHESTPLATE")).map { it.id.value }
            assertEquals(listOf("IRON"), sets)
        }
    }
}
