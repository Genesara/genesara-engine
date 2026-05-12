package dev.gvart.genesara.world.internal.equipment

import dev.gvart.genesara.world.EquipSlot
import dev.gvart.genesara.world.EquipmentSet
import dev.gvart.genesara.world.EquipmentSetId
import dev.gvart.genesara.world.EquipmentSetLookup
import dev.gvart.genesara.world.EquipmentSetThreshold
import dev.gvart.genesara.world.EquippedBonus
import dev.gvart.genesara.world.DamageType
import dev.gvart.genesara.world.Item
import dev.gvart.genesara.world.ItemCategory
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.ItemLookup
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EquipmentSetReferentialValidatorTest {

    @Test
    fun `passes when every piece is an EQUIPMENT-category item in the catalog`() {
        val helmet = equipmentItem("IRON_HELMET")
        val chest = equipmentItem("IRON_CHEST")
        val validator = EquipmentSetReferentialValidator(
            sets = StubSets(setOf(setOf(helmet.id, chest.id))),
            items = StubItems(listOf(helmet, chest)),
        )
        validator.validate()
    }

    @Test
    fun `fails when a piece is missing from the items catalog`() {
        val helmet = equipmentItem("IRON_HELMET")
        val validator = EquipmentSetReferentialValidator(
            sets = StubSets(setOf(setOf(helmet.id, ItemId("PHANTOM_PIECE")))),
            items = StubItems(listOf(helmet)),
        )
        val ex = assertFailsWith<IllegalArgumentException> { validator.validate() }
        assertTrue("PHANTOM_PIECE" in ex.message!!)
    }

    @Test
    fun `fails when a piece is RESOURCE category instead of EQUIPMENT`() {
        val helmet = equipmentItem("IRON_HELMET")
        val woodId = ItemId("WOOD")
        val wood = Item(
            id = woodId, displayName = "Wood", description = "",
            category = ItemCategory.RESOURCE, weightPerUnit = 100, maxStack = 99,
        )
        val validator = EquipmentSetReferentialValidator(
            sets = StubSets(setOf(setOf(helmet.id, woodId))),
            items = StubItems(listOf(helmet, wood)),
        )
        val ex = assertFailsWith<IllegalArgumentException> { validator.validate() }
        assertTrue("WOOD" in ex.message!! && "RESOURCE" in ex.message!!)
    }

    private fun equipmentItem(id: String) = Item(
        id = ItemId(id),
        displayName = id,
        description = "",
        category = ItemCategory.EQUIPMENT,
        weightPerUnit = 0,
        maxStack = 1,
        validSlots = setOf(EquipSlot.HELMET),
        maxDurability = 100,
    )

    private class StubItems(items: List<Item>) : ItemLookup {
        private val byId = items.associateBy { it.id }
        override fun byId(id: ItemId): Item? = byId[id]
        override fun all(): List<Item> = byId.values.toList()
    }

    private class StubSets(private val setsAsPieces: Set<Set<ItemId>>) : EquipmentSetLookup {
        private val builtSets: List<EquipmentSet> = setsAsPieces.mapIndexed { idx, pieces ->
            EquipmentSet(
                id = EquipmentSetId("SET_$idx"),
                pieces = pieces,
                thresholds = mapOf(1 to EquipmentSetThreshold(
                    bonuses = listOf(EquippedBonus.ArmorDef(DamageType.SLASH, 1)),
                )),
            )
        }
        override fun byId(id: EquipmentSetId): EquipmentSet? = builtSets.firstOrNull { it.id == id }
        override fun all(): List<EquipmentSet> = builtSets
        override fun setsContaining(itemId: ItemId): List<EquipmentSet> =
            builtSets.filter { itemId in it.pieces }
    }
}
