package dev.gvart.genesara.player.internal.race

import dev.gvart.genesara.player.RaceId
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RaceLookupImplTest {

    private val props = RaceDefinitionProperties(
        defaultId = "human_commoner",
        catalog = mapOf(
            "human_commoner" to RaceProperties(
                displayName = "Commoner",
                weight = 50,
                description = "Everyman.",
            ),
            "human_steppe" to RaceProperties(
                displayName = "Steppe-born",
                weight = 30,
                description = "Wide horizons.",
                attributeMods = AttributeModsProperties(
                    strength = -1,
                    dexterity = 1,
                    perception = 1,
                ),
            ),
        ),
    )
    private val lookup = RaceLookupImpl(props)

    @Test
    fun `byId returns a Race assembled from properties`() {
        val race = assertNotNull(lookup.byId(RaceId("human_steppe")))
        assertEquals("Steppe-born", race.displayName)
        assertEquals(30, race.weight)
        assertEquals("Wide horizons.", race.description)
        assertEquals(-1, race.attributeMods.strength)
        assertEquals(1, race.attributeMods.dexterity)
        assertEquals(1, race.attributeMods.perception)
    }

    @Test
    fun `byId returns null for unknown id`() {
        assertNull(lookup.byId(RaceId("alien_unknown")))
    }

    @Test
    fun `all returns every catalog entry`() {
        val ids = lookup.all().map { it.id.value }.toSet()
        assertEquals(setOf("human_commoner", "human_steppe"), ids)
    }
}
