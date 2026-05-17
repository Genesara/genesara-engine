package dev.gvart.genesara.world.internal.inventory

import dev.gvart.genesara.world.ItemId
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class AgentInventoryTest {

    private val wood = ItemId("WOOD")
    private val stone = ItemId("STONE")

    @Test
    fun `add creates a new stack when the item is absent`() {
        val inv = AgentInventory.EMPTY.add(wood, 3)
        assertEquals(3, inv.quantityOf(wood))
        assertEquals(0, inv.quantityOf(stone))
    }

    @Test
    fun `add accumulates quantity onto an existing stack`() {
        val inv = AgentInventory.EMPTY.add(wood, 2).add(wood, 5)
        assertEquals(7, inv.quantityOf(wood))
    }

    @Test
    fun `add does not touch other stacks`() {
        val inv = AgentInventory.EMPTY.add(wood, 4).add(stone, 1)
        assertEquals(4, inv.quantityOf(wood))
        assertEquals(1, inv.quantityOf(stone))
    }

    @Test
    fun `add rejects non-positive quantities`() {
        assertThrows<IllegalArgumentException> { AgentInventory.EMPTY.add(wood, 0) }
        assertThrows<IllegalArgumentException> { AgentInventory.EMPTY.add(wood, -1) }
    }
}
