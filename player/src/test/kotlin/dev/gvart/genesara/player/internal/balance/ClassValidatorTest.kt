package dev.gvart.genesara.player.internal.balance

import dev.gvart.genesara.player.AgentClass
import dev.gvart.genesara.player.LevelEffect
import dev.gvart.genesara.player.ScalingEffect
import dev.gvart.genesara.player.Skill
import dev.gvart.genesara.player.SkillCategory
import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.player.SkillLookup
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ClassValidatorTest {

    private val knownSkills = setOf("SWORD", "BOW", "FORAGING", "FIREARMS").map { SkillId(it) }.toSet()

    @Test
    fun `every AgentClass entry must have YAML — missing entry fails loud`() {
        val props = props(mapOf(AgentClass.SOLDIER to soldierShape()))
        val ex = assertFailsWith<IllegalArgumentException> {
            ClassValidator(props, fakeSkills()).validate()
        }
        assertTrue(ex.message!!.contains("SCOUT: no entry"))
    }

    @Test
    fun `unknown skill id in primary-skills surfaces a readable message`() {
        val broken = soldierShape().copy(primarySkills = listOf("SWORD", "NOT_A_SKILL"))
        val ex = assertFailsWith<IllegalArgumentException> {
            ClassValidator(props(allClasses(broken)), fakeSkills()).validate()
        }
        assertTrue(ex.message!!.contains("primary-skills references unknown skill id 'NOT_A_SKILL'"))
    }

    @Test
    fun `non-positive sight-range fails`() {
        val broken = soldierShape().copy(sightRange = 0)
        val ex = assertFailsWith<IllegalArgumentException> {
            ClassValidator(props(allClasses(broken)), fakeSkills()).validate()
        }
        assertTrue(ex.message!!.contains("sight-range must be > 0"))
    }

    @Test
    fun `non-positive damage multiplier fails`() {
        val broken = soldierShape().copy(damageMultipliers = mapOf("SLASH" to 0.0))
        val ex = assertFailsWith<IllegalArgumentException> {
            ClassValidator(props(allClasses(broken)), fakeSkills()).validate()
        }
        assertTrue(ex.message!!.contains("damage-multipliers.SLASH must be > 0"))
    }

    @Test
    fun `negative behavior fingerprint weight fails`() {
        val broken = soldierShape().copy(behaviorFingerprint = mapOf("COMBAT" to -0.1))
        val ex = assertFailsWith<IllegalArgumentException> {
            ClassValidator(props(allClasses(broken)), fakeSkills()).validate()
        }
        assertTrue(ex.message!!.contains("behavior-fingerprint.COMBAT must be >= 0"))
    }

    @Test
    fun `duplicate skill within primary list is rejected`() {
        val broken = soldierShape().copy(primarySkills = listOf("SWORD", "SWORD", "BOW"))
        val ex = assertFailsWith<IllegalArgumentException> {
            ClassValidator(props(allClasses(broken)), fakeSkills()).validate()
        }
        assertTrue(ex.message!!.contains("primary-skills has duplicate entries"))
    }

    @Test
    fun `same skill in both primary and neutral is rejected`() {
        val broken = soldierShape().copy(
            primarySkills = listOf("SWORD", "BOW"),
            neutralSkills = listOf("SWORD"),
        )
        val ex = assertFailsWith<IllegalArgumentException> {
            ClassValidator(props(allClasses(broken)), fakeSkills()).validate()
        }
        assertTrue(ex.message!!.contains("appear in both primary and neutral lists"))
    }

    @Test
    fun `evolution declaring an unknown parent is rejected`() {
        val orphanEvolution = soldierShape().copy(parentClass = AgentClass.MERCHANT)
        val classes = allClasses(soldierShape()).toMutableMap().apply {
            this[AgentClass.HEAVY_SOLDIER] = orphanEvolution
        }
        val ex = assertFailsWith<IllegalArgumentException> {
            ClassValidator(props(classes), fakeSkills()).validate()
        }
        assertTrue(
            ex.message!!.contains("HEAVY_SOLDIER: parent-class MERCHANT does not list HEAVY_SOLDIER"),
            "unexpected message: ${ex.message}",
        )
    }

    @Test
    fun `parent listing a non-matching evolution is rejected`() {
        val parent = soldierShape().copy(evolutions = listOf(AgentClass.RANGER))
        val notMyParent = soldierShape().copy(parentClass = AgentClass.SCOUT)
        val classes = allClasses(soldierShape()).toMutableMap().apply {
            this[AgentClass.SOLDIER] = parent
            this[AgentClass.RANGER] = notMyParent
        }
        val ex = assertFailsWith<IllegalArgumentException> {
            ClassValidator(props(classes), fakeSkills()).validate()
        }
        assertTrue(ex.message!!.contains("RANGER's parent-class is SCOUT"))
    }

    @Test
    fun `chained evolution (parent itself is an evolution) is rejected`() {
        val baseSoldier = soldierShape().copy(evolutions = listOf(AgentClass.HEAVY_SOLDIER))
        val midEvolution = soldierShape().copy(
            parentClass = AgentClass.SOLDIER,
            evolutions = listOf(AgentClass.SNIPER),
        )
        val grandchild = soldierShape().copy(parentClass = AgentClass.HEAVY_SOLDIER)
        val classes = allClasses(soldierShape()).toMutableMap().apply {
            this[AgentClass.SOLDIER] = baseSoldier
            this[AgentClass.HEAVY_SOLDIER] = midEvolution
            this[AgentClass.SNIPER] = grandchild
        }
        val ex = assertFailsWith<IllegalArgumentException> {
            ClassValidator(props(classes), fakeSkills()).validate()
        }
        assertTrue(ex.message!!.contains("chained evolutions are out of scope"))
    }

    @Test
    fun `self-listing as evolution is rejected`() {
        val brokenSelf = soldierShape().copy(evolutions = listOf(AgentClass.SOLDIER))
        val classes = allClasses(soldierShape()).toMutableMap().apply {
            this[AgentClass.SOLDIER] = brokenSelf
        }
        val ex = assertFailsWith<IllegalArgumentException> {
            ClassValidator(props(classes), fakeSkills()).validate()
        }
        assertTrue(ex.message!!.contains("SOLDIER: lists itself as an evolution"))
    }

    @Test
    fun `evolution missing a parent forbid is rejected`() {
        val researcher = soldierShape().copy(
            forbiddenCombatSkills = listOf("FIREARMS"),
            evolutions = listOf(AgentClass.SCHOLAR),
        )
        val scholar = soldierShape().copy(
            parentClass = AgentClass.RESEARCHER,
            // Forgets to inherit FIREARMS — would silently let scholars wield rifles.
            forbiddenCombatSkills = emptyList(),
        )
        val classes = allClasses(soldierShape()).toMutableMap().apply {
            this[AgentClass.RESEARCHER] = researcher
            this[AgentClass.SCHOLAR] = scholar
        }
        val ex = assertFailsWith<IllegalArgumentException> {
            ClassValidator(props(classes), fakeSkills()).validate()
        }
        assertTrue(ex.message!!.contains("SCHOLAR: missing parent-class RESEARCHER's forbidden-combat-skills"))
        assertTrue(ex.message!!.contains("FIREARMS"))
    }

    private fun soldierShape() = ClassProperties(
        displayName = "Soldier",
        description = "Frontline pro.",
        sightRange = 3,
        primarySkills = listOf("SWORD"),
        neutralSkills = listOf("BOW"),
        forbiddenCombatSkills = emptyList(),
        damageMultipliers = mapOf("SLASH" to 1.1),
        behaviorFingerprint = mapOf("COMBAT" to 1.0),
    )

    private fun allClasses(template: ClassProperties): Map<AgentClass, ClassProperties> =
        AgentClass.entries.associateWith { template }

    private fun props(classes: Map<AgentClass, ClassProperties>) =
        ClassDefinitionProperties(classes = classes)

    private fun fakeSkills() = object : SkillLookup {
        override fun byId(id: SkillId): Skill? = if (id in knownSkills) stubSkill(id) else null
        override fun all(): List<Skill> = knownSkills.map(::stubSkill)
    }

    private fun stubSkill(id: SkillId) = Skill(
        id = id,
        displayName = id.value,
        description = "stub",
        category = SkillCategory.COMBAT,
        levelEffect = LevelEffect(type = ScalingEffect.SLASH_DAMAGE_BONUS, perLevelPct = 0.005),
    )
}
