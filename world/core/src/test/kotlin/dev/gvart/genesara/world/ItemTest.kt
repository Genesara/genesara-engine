package dev.gvart.genesara.world

import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

/**
 * Init-block invariants on [Item]. The catalog YAML loader doesn't enforce
 * these directly — so a malformed entry would surface only at runtime when an
 * agent tries to equip it. The init block is the fence that fails fast at
 * boot instead.
 */
class ItemTest {

    @Test
    fun `two-handed item must include MAIN_HAND in validSlots`() {
        assertFailsWith<IllegalArgumentException> {
            base(twoHanded = true, validSlots = setOf(EquipSlot.HELMET))
        }
    }

    @Test
    fun `two-handed item cannot list OFF_HAND in validSlots`() {
        assertFailsWith<IllegalArgumentException> {
            base(twoHanded = true, validSlots = setOf(EquipSlot.MAIN_HAND, EquipSlot.OFF_HAND))
        }
    }

    @Test
    fun `two-handed item with only MAIN_HAND is well-formed`() {
        // Smoke test: the canonical catalog shape doesn't throw.
        base(twoHanded = true, validSlots = setOf(EquipSlot.MAIN_HAND))
    }

    @Test
    fun `single-hand item with OFF_HAND in validSlots is well-formed (e g shield)`() {
        // Single-hand items can be off-hand-only or main+off — only two-handed
        // weapons trigger the OFF_HAND lock.
        base(twoHanded = false, validSlots = setOf(EquipSlot.OFF_HAND))
        base(twoHanded = false, validSlots = setOf(EquipSlot.MAIN_HAND, EquipSlot.OFF_HAND))
    }

    private fun base(twoHanded: Boolean, validSlots: Set<EquipSlot>) = Item(
        id = ItemId("TEST_ITEM"),
        displayName = "Test",
        description = "",
        category = ItemCategory.EQUIPMENT,
        weightPerUnit = 100,
        maxStack = 1,
        regenerating = false,
        rarity = Rarity.COMMON,
        maxDurability = 50,
        validSlots = validSlots,
        twoHanded = twoHanded,
    )
}
