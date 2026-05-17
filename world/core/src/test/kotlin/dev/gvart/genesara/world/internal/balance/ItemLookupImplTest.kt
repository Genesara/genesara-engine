package dev.gvart.genesara.world.internal.balance

import dev.gvart.genesara.player.Attribute
import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.world.EquipSlot
import dev.gvart.genesara.world.ItemCategory
import dev.gvart.genesara.world.ItemId
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ItemLookupImplTest {

    private val props = ItemDefinitionProperties(
        catalog = mapOf(
            "WOOD" to ItemProperties(
                displayName = "Wood",
                description = "Rough timber.",
                category = ItemCategory.RESOURCE,
                weightPerUnit = 800,
                maxStack = 200,
            ),
            "BERRY" to ItemProperties(
                displayName = "Wild Berries",
                description = "Edible forage.",
                category = ItemCategory.RESOURCE,
                weightPerUnit = 50,
                maxStack = 100,
            ),
        ),
    )
    private val lookup = ItemLookupImpl(props)

    @Test
    fun `byId returns an Item assembled from properties`() {
        val item = assertNotNull(lookup.byId(ItemId("WOOD")))
        assertEquals("Wood", item.displayName)
        assertEquals("Rough timber.", item.description)
        assertEquals(ItemCategory.RESOURCE, item.category)
        assertEquals(800, item.weightPerUnit)
        assertEquals(200, item.maxStack)
    }

    @Test
    fun `byId returns null for unknown id`() {
        assertNull(lookup.byId(ItemId("PHANTOM")))
    }

    @Test
    fun `all returns every catalog entry`() {
        val ids = lookup.all().map { it.id.value }.toSet()
        assertEquals(setOf("WOOD", "BERRY"), ids)
    }

    @Test
    fun `equipment-class fields round-trip from properties to Item`() {
        // Pin the C1 + C2 catalog → domain mapping for equipment items —
        // validSlots, twoHanded, requiredAttributes, and requiredSkills (with
        // string keys decoded into typed SkillId at the lookup layer).
        val props = ItemDefinitionProperties(
            catalog = mapOf(
                "IRON_GREATSWORD" to ItemProperties(
                    displayName = "Iron Greatsword",
                    description = "Two-handed.",
                    category = ItemCategory.EQUIPMENT,
                    weightPerUnit = 4000,
                    maxStack = 1,
                    regenerating = false,
                    maxDurability = 120,
                    validSlots = setOf(EquipSlot.MAIN_HAND),
                    twoHanded = true,
                    requiredAttributes = mapOf(Attribute.STRENGTH to 12),
                    requiredSkills = mapOf("WEAPONRY" to 30),
                ),
            ),
        )
        val lookup = ItemLookupImpl(props)

        val item = assertNotNull(lookup.byId(ItemId("IRON_GREATSWORD")))
        assertEquals(setOf(EquipSlot.MAIN_HAND), item.validSlots)
        assertEquals(true, item.twoHanded)
        assertEquals(mapOf(Attribute.STRENGTH to 12), item.requiredAttributes)
        assertEquals(mapOf(SkillId("WEAPONRY") to 30), item.requiredSkills)
    }
}
