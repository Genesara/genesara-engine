package dev.gvart.genesara.world.body.internal.equipment

import dev.gvart.genesara.world.EquipmentSetId
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.internal.balance.EquippedBonusProperties
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class EquipmentSetLookupImplTest {

    @Test
    fun `byId returns the bound set or null`() {
        val lookup = lookupOf(
            "IRON" to setProps(
                pieces = listOf("IRON_HELMET", "IRON_CHEST"),
                thresholds = mapOf(2 to listOf(EquippedBonusProperties("SLASH", 3))),
            ),
        )
        assertNotNull(lookup.byId(EquipmentSetId("IRON")))
        assertNull(lookup.byId(EquipmentSetId("PHANTOM")))
    }

    @Test
    fun `setsContaining reverse-indexes items to their containing sets`() {
        val lookup = lookupOf(
            "IRON" to setProps(
                pieces = listOf("IRON_HELMET", "IRON_CHEST"),
                thresholds = mapOf(2 to listOf(EquippedBonusProperties("SLASH", 3))),
            ),
            "STEEL" to setProps(
                pieces = listOf("STEEL_HELMET", "IRON_CHEST"),
                thresholds = mapOf(2 to listOf(EquippedBonusProperties("PIERCE", 2))),
            ),
        )
        val sharedItem = ItemId("IRON_CHEST")
        val sets = lookup.setsContaining(sharedItem).map { it.id.value }.toSet()
        assertEquals(setOf("IRON", "STEEL"), sets)
    }

    @Test
    fun `unknown bonus target in a threshold fails at construction time`() {
        val ex = assertFailsWith<IllegalStateException> {
            lookupOf(
                "IRON" to setProps(
                    pieces = listOf("IRON_HELMET"),
                    thresholds = mapOf(1 to listOf(EquippedBonusProperties("PHANTOM_TARGET", 1))),
                ),
            )
        }
        kotlin.test.assertTrue("PHANTOM_TARGET" in ex.message!!)
    }

    @Test
    fun `threshold tier above piece count fails the EquipmentSet require`() {
        // tier 3 on a 2-piece set is invalid per EquipmentSet.init
        val ex = assertFailsWith<IllegalArgumentException> {
            lookupOf(
                "IRON" to setProps(
                    pieces = listOf("A", "B"),
                    thresholds = mapOf(3 to listOf(EquippedBonusProperties("SLASH", 1))),
                ),
            )
        }
        kotlin.test.assertTrue("threshold tier 3" in ex.message!!)
    }

    private fun setProps(pieces: List<String>, thresholds: Map<Int, List<EquippedBonusProperties>>): EquipmentSetProperties =
        EquipmentSetProperties(
            pieces = pieces,
            thresholds = thresholds.mapValues { (_, bonuses) -> EquipmentSetThresholdProperties(bonuses) },
        )

    private fun lookupOf(vararg sets: Pair<String, EquipmentSetProperties>): EquipmentSetLookupImpl =
        EquipmentSetLookupImpl(EquipmentSetDefinitionProperties(catalog = sets.toMap()))
}
