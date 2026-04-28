package dev.gvart.genesara.player.internal.race

import dev.gvart.genesara.player.AttributeMods
import dev.gvart.genesara.player.Race
import dev.gvart.genesara.player.RaceId
import dev.gvart.genesara.player.RaceLookup
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertSame

class RaceAssignerTest {

    private val commoner = race("human_commoner", weight = 50)
    private val steppe = race("human_steppe", weight = 30)
    private val highland = race("human_highland", weight = 20)

    @Test
    fun `picks the race whose cumulative weight covers the roll`() {
        val lookup = StubLookup(listOf(commoner, steppe, highland))
        val props = RaceDefinitionProperties(defaultId = "human_commoner")

        // Total weight = 100. Rolls: 0..49 → commoner, 50..79 → steppe, 80..99 → highland.
        assertSame(commoner, RaceAssigner(lookup, props, FixedRandom(0)).assign())
        assertSame(commoner, RaceAssigner(lookup, props, FixedRandom(49)).assign())
        assertSame(steppe, RaceAssigner(lookup, props, FixedRandom(50)).assign())
        assertSame(steppe, RaceAssigner(lookup, props, FixedRandom(79)).assign())
        assertSame(highland, RaceAssigner(lookup, props, FixedRandom(80)).assign())
        assertSame(highland, RaceAssigner(lookup, props, FixedRandom(99)).assign())
    }

    @Test
    fun `falls back to the default race when no race has positive weight`() {
        val zeroWeight = race("human_commoner", weight = 0)
        val lookup = StubLookup(listOf(zeroWeight))
        val props = RaceDefinitionProperties(defaultId = "human_commoner")

        val result = RaceAssigner(lookup, props, FixedRandom(0)).assign()

        assertEquals(zeroWeight.id, result.id)
    }

    @Test
    fun `skips zero-weight races sitting between weighted ones`() {
        // [50, 0, 50] — the middle race is invisible to the picker; rolls 0..49 → first,
        // 50..99 → third. Pins the filter behaviour against a regression that removes it.
        val first = race("first", weight = 50)
        val middle = race("middle", weight = 0)
        val third = race("third", weight = 50)
        val lookup = StubLookup(listOf(first, middle, third))
        val props = RaceDefinitionProperties(defaultId = "first")

        assertSame(first, RaceAssigner(lookup, props, FixedRandom(0)).assign())
        assertSame(first, RaceAssigner(lookup, props, FixedRandom(49)).assign())
        assertSame(third, RaceAssigner(lookup, props, FixedRandom(50)).assign())
        assertSame(third, RaceAssigner(lookup, props, FixedRandom(99)).assign())
    }

    @Test
    fun `treats negative-weight races the same as zero-weight (filtered out)`() {
        val negative = race("negative", weight = -10)
        val positive = race("positive", weight = 7)
        val lookup = StubLookup(listOf(negative, positive))
        val props = RaceDefinitionProperties(defaultId = "negative")

        assertSame(positive, RaceAssigner(lookup, props, FixedRandom(0)).assign())
        assertSame(positive, RaceAssigner(lookup, props, FixedRandom(6)).assign())
    }

    @Test
    fun `errors when catalog is empty and the default id is missing`() {
        val lookup = StubLookup(emptyList())
        val props = RaceDefinitionProperties(defaultId = "nonexistent")
        val assigner = RaceAssigner(lookup, props, FixedRandom(0))

        assertThrows<IllegalStateException> { assigner.assign() }
    }

    private fun race(id: String, weight: Int): Race = Race(
        id = RaceId(id),
        displayName = id,
        weight = weight,
        attributeMods = AttributeMods.NONE,
        description = "",
    )

    private class StubLookup(private val races: List<Race>) : RaceLookup {
        override fun byId(id: RaceId): Race? = races.firstOrNull { it.id == id }
        override fun all(): List<Race> = races
    }

    private class FixedRandom(private val value: Int) : RandomSource {
        override fun nextInt(boundExclusive: Int): Int = value.coerceIn(0, boundExclusive - 1)
    }
}
