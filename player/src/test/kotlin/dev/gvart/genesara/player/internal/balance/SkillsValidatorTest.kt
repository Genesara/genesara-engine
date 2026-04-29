package dev.gvart.genesara.player.internal.balance

import dev.gvart.genesara.player.Skill
import dev.gvart.genesara.player.SkillCategory
import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.player.SkillLookup
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertTrue

class SkillsValidatorTest {

    @Test
    fun `accepts a well-formed catalog`() {
        val lookup = StubLookup(
            listOf(
                Skill(SkillId("FORAGING"), "Foraging", "plant gathering", SkillCategory.GATHERING),
                Skill(SkillId("MINING"), "Mining", "rock breaking", SkillCategory.GATHERING),
            ),
        )
        SkillsValidator(lookup).validate()
    }

    @Test
    fun `rejects an entry with blank display name`() {
        val lookup = StubLookup(
            listOf(Skill(SkillId("FORAGING"), "", "non-empty", SkillCategory.GATHERING)),
        )
        val ex = assertThrows<IllegalArgumentException> { SkillsValidator(lookup).validate() }
        assertTrue(ex.message?.contains("FORAGING") == true)
        assertTrue(ex.message?.contains("display-name") == true)
    }

    @Test
    fun `rejects an entry with blank description`() {
        val lookup = StubLookup(
            listOf(Skill(SkillId("MINING"), "Mining", "", SkillCategory.GATHERING)),
        )
        val ex = assertThrows<IllegalArgumentException> { SkillsValidator(lookup).validate() }
        assertTrue(ex.message?.contains("MINING") == true)
        assertTrue(ex.message?.contains("description") == true)
    }

    @Test
    fun `rejects an empty catalog so misconfiguration fails fast at startup`() {
        // An empty catalog would silently no-op every gather XP grant — fail fast.
        val ex = assertThrows<IllegalArgumentException> {
            SkillsValidator(StubLookup(emptyList())).validate()
        }
        assertTrue(ex.message?.contains("empty") == true)
    }

    private class StubLookup(private val skills: List<Skill>) : SkillLookup {
        private val byId = skills.associateBy { it.id }
        override fun byId(id: SkillId): Skill? = byId[id]
        override fun all(): List<Skill> = skills
    }
}
