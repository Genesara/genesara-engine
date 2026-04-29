package dev.gvart.genesara.player.internal.balance

import dev.gvart.genesara.player.SkillCategory
import dev.gvart.genesara.player.SkillId
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SkillLookupImplTest {

    private val props = SkillDefinitionProperties(
        catalog = mapOf(
            "FORAGING" to SkillProperties(
                displayName = "Foraging",
                description = "plant and fungus gathering",
                category = SkillCategory.GATHERING,
            ),
            "SWORD" to SkillProperties(
                displayName = "Swordsmanship",
                description = "one-handed slashing weapons",
                category = SkillCategory.COMBAT,
            ),
        ),
    )
    private val lookup = SkillLookupImpl(props)

    @Test
    fun `byId returns a Skill assembled from properties`() {
        val skill = assertNotNull(lookup.byId(SkillId("FORAGING")))
        assertEquals("Foraging", skill.displayName)
        assertEquals("plant and fungus gathering", skill.description)
        assertEquals(SkillCategory.GATHERING, skill.category)
    }

    @Test
    fun `byId returns null for unknown id`() {
        assertNull(lookup.byId(SkillId("PHANTOM")))
    }

    @Test
    fun `all returns every catalog entry`() {
        val ids = lookup.all().map { it.id.value }.toSet()
        assertEquals(setOf("FORAGING", "SWORD"), ids)
    }
}
