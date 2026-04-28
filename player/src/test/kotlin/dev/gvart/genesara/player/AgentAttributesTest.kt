package dev.gvart.genesara.player

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class AgentAttributesTest {

    @Test
    fun `mods add to attributes element-wise`() {
        val attrs = AgentAttributes(strength = 3, dexterity = 4)
        val result = attrs + AttributeMods(strength = 2, dexterity = -1, intelligence = 5)
        assertEquals(5, result.strength)
        assertEquals(3, result.dexterity)
        assertEquals(6, result.intelligence)
    }

    @Test
    fun `mods clamp attributes to MIN_ATTRIBUTE`() {
        val attrs = AgentAttributes.DEFAULT.copy(strength = 1)
        val result = attrs + AttributeMods(strength = -10)
        assertEquals(AgentAttributes.MIN_ATTRIBUTE, result.strength)
    }

    @Test
    fun `NONE mods leave attributes unchanged`() {
        val attrs = AgentAttributes(strength = 7, intelligence = 9)
        assertEquals(attrs, attrs + AttributeMods.NONE)
    }
}
