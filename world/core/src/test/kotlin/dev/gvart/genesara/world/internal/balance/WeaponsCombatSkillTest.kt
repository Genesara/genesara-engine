package dev.gvart.genesara.world.internal.balance

import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.world.ItemId
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.ConfigurationPropertiesBindingPostProcessor
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class WeaponsCombatSkillTest {

    @Test
    fun `every weapon item in the catalog declares a combat-skill so attacks dont silently route to UNARMED`() {
        AnnotationConfigApplicationContext().use { ctx ->
            ConfigurationPropertiesBindingPostProcessor.register(ctx)
            ctx.register(ItemBalanceConfiguration::class.java)
            ctx.refresh()

            val lookup = ItemLookupImpl(ctx.getBean(ItemDefinitionProperties::class.java))

            fun combatSkillOf(id: String) =
                assertNotNull(lookup.byId(ItemId(id)), "weapon $id missing from catalog").combatSkill

            assertEquals(SkillId("SWORD"), combatSkillOf("RUSTY_SWORD"))
            assertEquals(SkillId("SWORD"), combatSkillOf("IRON_SWORD"))
            assertEquals(SkillId("SWORD"), combatSkillOf("IRON_GREATSWORD"))
            assertEquals(SkillId("BOW"), combatSkillOf("WOODEN_BOW"))
            assertEquals(SkillId("BOW"), combatSkillOf("COMPOSITE_BOW"))
            assertEquals(SkillId("CLUB"), combatSkillOf("WOODEN_CLUB"))
            assertEquals(SkillId("CLUB"), combatSkillOf("WAR_HAMMER"))
            assertEquals(SkillId("SPEAR"), combatSkillOf("HUNTING_SPEAR"))
            assertEquals(SkillId("DAGGER"), combatSkillOf("IRON_DAGGER"))
        }
    }
}
