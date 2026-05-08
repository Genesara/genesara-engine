package dev.gvart.genesara.player.internal.balance

import dev.gvart.genesara.player.LevelEffect
import dev.gvart.genesara.player.ScalingEffect
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
        SkillsValidator(lookup, emptyProps()).validate()
    }

    @Test
    fun `rejects an entry with blank display name`() {
        val lookup = StubLookup(
            listOf(Skill(SkillId("FORAGING"), "", "non-empty", SkillCategory.GATHERING)),
        )
        val ex = assertThrows<IllegalArgumentException> { SkillsValidator(lookup, emptyProps()).validate() }
        assertTrue(ex.message?.contains("FORAGING") == true)
        assertTrue(ex.message?.contains("display-name") == true)
    }

    @Test
    fun `rejects an entry with blank description`() {
        val lookup = StubLookup(
            listOf(Skill(SkillId("MINING"), "Mining", "", SkillCategory.GATHERING)),
        )
        val ex = assertThrows<IllegalArgumentException> { SkillsValidator(lookup, emptyProps()).validate() }
        assertTrue(ex.message?.contains("MINING") == true)
        assertTrue(ex.message?.contains("description") == true)
    }

    @Test
    fun `rejects an empty catalog so misconfiguration fails fast at startup`() {
        val ex = assertThrows<IllegalArgumentException> {
            SkillsValidator(StubLookup(emptyList()), emptyProps()).validate()
        }
        assertTrue(ex.message?.contains("empty") == true)
    }

    @Test
    fun `rejects a level-effect with non-positive per-level pct`() {
        val lookup = StubLookup(
            listOf(
                Skill(
                    SkillId("SWORD"), "Swords", "blades", SkillCategory.COMBAT,
                    levelEffect = LevelEffect(ScalingEffect.SLASH_DAMAGE_BONUS, perLevelPct = 0.0),
                ),
            ),
        )
        val ex = assertThrows<IllegalArgumentException> { SkillsValidator(lookup, emptyProps()).validate() }
        assertTrue(ex.message?.contains("SWORD") == true)
        assertTrue(ex.message?.contains("per-level-pct") == true)
    }

    @Test
    fun `rejects a level-effect block with type set but per-level-pct missing`() {
        val lookup = StubLookup(
            listOf(Skill(SkillId("SWORD"), "Swords", "blades", SkillCategory.COMBAT)),
        )
        val props = SkillDefinitionProperties(
            catalog = mapOf(
                "SWORD" to SkillProperties(
                    displayName = "Swords",
                    description = "blades",
                    category = SkillCategory.COMBAT,
                    levelEffect = LevelEffectProperties(type = ScalingEffect.SLASH_DAMAGE_BONUS, perLevelPct = null),
                ),
            ),
        )
        val ex = assertThrows<IllegalArgumentException> { SkillsValidator(lookup, props).validate() }
        assertTrue(ex.message?.contains("SWORD") == true)
        assertTrue(ex.message?.contains("per-level-pct") == true)
    }

    private fun emptyProps(): SkillDefinitionProperties = SkillDefinitionProperties(catalog = emptyMap())

    private class StubLookup(private val skills: List<Skill>) : SkillLookup {
        private val byId = skills.associateBy { it.id }
        override fun byId(id: SkillId): Skill? = byId[id]
        override fun all(): List<Skill> = skills
    }
}
