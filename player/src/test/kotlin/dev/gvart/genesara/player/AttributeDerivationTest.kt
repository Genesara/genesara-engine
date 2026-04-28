package dev.gvart.genesara.player

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class AttributeDerivationTest {

    @Test
    fun `derives baseline pools for default attributes`() {
        val pools = AttributeDerivation.deriveMaxPools(AgentAttributes.DEFAULT)
        // HP_BASE 50 + 1 * HP_PER_CON 10 = 60
        assertEquals(60, pools.maxHp)
        // STAMINA_BASE 30 + (1 + 1) * STAMINA_PER_PT 5 = 40
        assertEquals(40, pools.maxStamina)
        // 1 * MANA_PER_INT 5 = 5
        assertEquals(5, pools.maxMana)
    }

    @Test
    fun `scales HP linearly with constitution`() {
        val attrs = AgentAttributes.DEFAULT.copy(constitution = 5)
        val pools = AttributeDerivation.deriveMaxPools(attrs)
        // 50 + 5 * 10 = 100
        assertEquals(100, pools.maxHp)
    }

    @Test
    fun `stamina scales with both constitution and dexterity`() {
        val attrs = AgentAttributes.DEFAULT.copy(constitution = 4, dexterity = 6)
        val pools = AttributeDerivation.deriveMaxPools(attrs)
        // 30 + (4 + 6) * 5 = 80
        assertEquals(80, pools.maxStamina)
    }

    @Test
    fun `mana scales with intelligence only`() {
        val attrs = AgentAttributes.DEFAULT.copy(intelligence = 10)
        val pools = AttributeDerivation.deriveMaxPools(attrs)
        // 10 * 5 = 50
        assertEquals(50, pools.maxMana)
        // HP/Stamina unchanged from default
        assertEquals(60, pools.maxHp)
        assertEquals(40, pools.maxStamina)
    }
}
