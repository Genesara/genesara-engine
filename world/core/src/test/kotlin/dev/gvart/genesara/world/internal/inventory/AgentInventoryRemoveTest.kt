package dev.gvart.genesara.world.internal.inventory

import dev.gvart.genesara.world.ItemId
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentInventoryRemoveTest {

    private val wood = ItemId("WOOD")
    private val stone = ItemId("STONE")

    @Test
    fun `remove decrements the stack and keeps it when remaining is positive`() {
        val inv = AgentInventory(mapOf(wood to 5)).remove(wood, 2)
        assertEquals(3, inv.quantityOf(wood))
        assertTrue(wood in inv.stacks)
    }

    @Test
    fun `remove drops the entry entirely when the stack reaches zero`() {
        val inv = AgentInventory(mapOf(wood to 3, stone to 1)).remove(wood, 3)
        assertEquals(0, inv.quantityOf(wood))
        assertTrue(wood !in inv.stacks)
        assertEquals(1, inv.quantityOf(stone))
    }

    @Test
    fun `remove with insufficient stack throws`() {
        val inv = AgentInventory(mapOf(wood to 1))
        assertThrows<IllegalArgumentException> { inv.remove(wood, 2) }
    }

    @Test
    fun `remove for an absent item throws`() {
        assertThrows<IllegalArgumentException> { AgentInventory.EMPTY.remove(wood, 1) }
    }

    @Test
    fun `remove rejects non-positive quantities`() {
        val inv = AgentInventory(mapOf(wood to 5))
        assertThrows<IllegalArgumentException> { inv.remove(wood, 0) }
        assertThrows<IllegalArgumentException> { inv.remove(wood, -1) }
    }
}
