package dev.gvart.genesara.world.internal.balance

import dev.gvart.genesara.player.AgentClass
import dev.gvart.genesara.player.ClassDefinition
import dev.gvart.genesara.player.ClassLookup
import dev.gvart.genesara.player.SkillId
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ClassCatalogConsistencyValidatorTest {

    @Test
    fun `unknown DamageType key is rejected`() {
        val def = soldier().copy(damageMultipliers = mapOf("SLASH" to 1.1, "PSIONIC" to 1.0))
        val ex = assertFailsWith<IllegalArgumentException> {
            ClassCatalogConsistencyValidator(StubClasses(listOf(def))).validate()
        }
        assertTrue(ex.message!!.contains("damage-multipliers.PSIONIC does not match any DamageType"))
    }

    @Test
    fun `unknown ActionCategory key is rejected`() {
        val def = soldier().copy(behaviorFingerprint = mapOf("COMBAT" to 1.0, "MAGIC" to 1.0))
        val ex = assertFailsWith<IllegalArgumentException> {
            ClassCatalogConsistencyValidator(StubClasses(listOf(def))).validate()
        }
        assertTrue(ex.message!!.contains("behavior-fingerprint.MAGIC does not match any ActionCategory"))
    }

    @Test
    fun `valid catalog passes`() {
        ClassCatalogConsistencyValidator(StubClasses(listOf(soldier()))).validate()
    }

    @Test
    fun `base class with fewer than 2 evolutions is rejected (L50 emitter prerequisite)`() {
        val onlyOne = soldier().copy(evolutions = listOf(AgentClass.HEAVY_SOLDIER))
        val ex = assertFailsWith<IllegalArgumentException> {
            ClassCatalogConsistencyValidator(StubClasses(listOf(onlyOne))).validate()
        }
        assertTrue(ex.message!!.contains("must declare >= 2 evolutions"))
    }

    private fun soldier() = ClassDefinition(
        id = AgentClass.SOLDIER,
        displayName = "Soldier",
        description = "Frontline pro.",
        sightRange = 3,
        primarySkills = setOf(SkillId("SWORD")),
        neutralSkills = emptySet(),
        forbiddenCombatSkills = emptySet(),
        damageMultipliers = mapOf("SLASH" to 1.1),
        behaviorFingerprint = mapOf("COMBAT" to 1.0),
        evolutions = listOf(AgentClass.HEAVY_SOLDIER, AgentClass.STEALTH_SOLDIER),
    )

    private class StubClasses(private val defs: List<ClassDefinition>) : ClassLookup {
        override fun byId(classId: AgentClass): ClassDefinition? = defs.firstOrNull { it.id == classId }
        override fun all(): List<ClassDefinition> = defs
        override fun baseClasses(): List<ClassDefinition> = defs.filter { it.parentClass == null }
        override fun evolutionsOf(parent: AgentClass): List<ClassDefinition> = emptyList()
        override fun sightRange(classId: AgentClass?): Int = 3
        override fun skillXpMultiplier(classId: AgentClass?, skill: SkillId): Double = 1.0
        override fun damageMultiplier(classId: AgentClass?, damageType: String): Double = 1.0
        override fun forbidsCombatSkill(classId: AgentClass?, combatSkill: SkillId): Boolean = false
    }
}
