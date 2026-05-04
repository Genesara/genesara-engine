package dev.gvart.genesara.world.internal.crafting

import dev.gvart.genesara.world.Rarity
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RarityRollerTest {

    @Test
    fun `floor of skill div ten sets the base tier with no luck`() {
        val roller = RarityRoller(Random(42L))
        assertEquals(Rarity.COMMON, roller.roll(skillLevel = 0, luck = 0))
        assertEquals(Rarity.UNCOMMON, roller.roll(skillLevel = 10, luck = 0))
        assertEquals(Rarity.RARE, roller.roll(skillLevel = 25, luck = 0))
        assertEquals(Rarity.EPIC, roller.roll(skillLevel = 35, luck = 0))
        assertEquals(Rarity.LEGENDARY, roller.roll(skillLevel = 45, luck = 0))
    }

    @Test
    fun `tier ceiling clamps at LEGENDARY for high skill plus luck`() {
        val roller = RarityRoller(Random(1L))
        repeat(50) {
            assertEquals(Rarity.LEGENDARY, roller.roll(skillLevel = 80, luck = 30))
        }
    }

    @Test
    fun `seeded roll is deterministic across runs`() {
        val a = RarityRoller(Random(123L))
        val b = RarityRoller(Random(123L))
        repeat(20) {
            assertEquals(a.roll(skillLevel = 12, luck = 8), b.roll(skillLevel = 12, luck = 8))
        }
    }

    @Test
    fun `with luck zero the roll is purely floor based and never exceeds the base tier`() {
        val roller = RarityRoller(Random(7L))
        repeat(100) {
            val rolled = roller.roll(skillLevel = 7, luck = 0)
            assertEquals(Rarity.COMMON, rolled)
        }
    }

    @Test
    fun `luck bump pushes some rolls above the base tier when probabilistically lucky`() {
        val roller = RarityRoller(Random(999L))
        val outcomes = (0 until 200).map { roller.roll(skillLevel = 0, luck = 20) }
        val nonCommon = outcomes.count { it != Rarity.COMMON }
        // Luck=20 → ~40% first-bump chance per craft; expected ~80 rolls above COMMON.
        // Tight bounds (60..100) to catch a regression that flips the multiplier.
        assertTrue(nonCommon in 60..100, "expected ~80 rolls above COMMON; got $nonCommon")
    }

    @Test
    fun `negative skill clamps to base tier zero, never below`() {
        val roller = RarityRoller(Random(1L))
        assertEquals(Rarity.COMMON, roller.roll(skillLevel = -5, luck = 0))
    }
}
