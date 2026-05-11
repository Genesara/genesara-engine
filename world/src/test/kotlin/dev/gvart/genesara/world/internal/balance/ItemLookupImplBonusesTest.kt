package dev.gvart.genesara.world.internal.balance

import dev.gvart.genesara.player.Attribute
import dev.gvart.genesara.player.ScalingEffect
import dev.gvart.genesara.world.DamageType
import dev.gvart.genesara.world.EquippedBonus
import dev.gvart.genesara.world.ItemCategory
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ItemLookupImplBonusesTest {

    @Test
    fun `damage-type target binds to ArmorDef`() {
        val item = itemWith(target = "SLASH", magnitude = 8)
        assertEquals(listOf(EquippedBonus.ArmorDef(DamageType.SLASH, 8)), item.bonuses)
    }

    @Test
    fun `attribute target binds to AttributeBonus`() {
        val item = itemWith(target = "CONSTITUTION", magnitude = 5)
        assertEquals(listOf(EquippedBonus.AttributeBonus(Attribute.CONSTITUTION, 5)), item.bonuses)
    }

    @Test
    fun `scaling-effect target binds to PassiveBuff`() {
        val item = itemWith(target = "STAMINA_REGEN", magnitude = 2)
        assertEquals(listOf(EquippedBonus.PassiveBuff(ScalingEffect.STAMINA_REGEN, 2)), item.bonuses)
    }

    @Test
    fun `unknown target throws with item id and bad target name`() {
        val ex = assertFailsWith<IllegalStateException> {
            itemWith(target = "PHANTOM_TARGET", magnitude = 1)
        }
        assertTrue("PHANTOM_TARGET" in ex.message!!)
        assertTrue("TEST" in ex.message!!)
    }

    @Test
    fun `mixed-kind list preserves order and each entry binds to its variant`() {
        val item = itemWith(
            ItemProperties(
                displayName = "Mixed",
                category = ItemCategory.EQUIPMENT,
                bonuses = listOf(
                    EquippedBonusProperties("SLASH", 8),
                    EquippedBonusProperties("CONSTITUTION", 3),
                    EquippedBonusProperties("STAMINA_REGEN", 1),
                ),
            ),
        )
        val kinds = item.bonuses.map { it::class.simpleName }
        assertEquals(listOf("ArmorDef", "AttributeBonus", "PassiveBuff"), kinds)
    }

    @Test
    fun `empty bonuses list produces an empty domain list`() {
        val item = itemWith(ItemProperties(displayName = "Plain", category = ItemCategory.RESOURCE))
        assertEquals(emptyList(), item.bonuses)
    }

    private fun itemWith(target: String, magnitude: Int) = itemWith(
        ItemProperties(
            displayName = "Stub",
            category = ItemCategory.EQUIPMENT,
            bonuses = listOf(EquippedBonusProperties(target, magnitude)),
        ),
    )

    private fun itemWith(props: ItemProperties): dev.gvart.genesara.world.Item {
        val lookup = ItemLookupImpl(ItemDefinitionProperties(catalog = mapOf("TEST" to props)))
        return lookup.byId(dev.gvart.genesara.world.ItemId("TEST"))
            ?: error("test item not bound")
    }
}
