package dev.gvart.genesara.player.internal.balance

import dev.gvart.genesara.player.AgentClass
import dev.gvart.genesara.player.SkillId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.context.properties.ConfigurationPropertiesBindingPostProcessor
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClassesYamlLoadingTest {

    private lateinit var ctx: AnnotationConfigApplicationContext
    private lateinit var lookup: ClassDefinitionLookup
    private lateinit var skills: SkillLookupImpl
    private lateinit var validator: ClassValidator

    @BeforeAll
    fun bootContext() {
        ctx = AnnotationConfigApplicationContext().also {
            ConfigurationPropertiesBindingPostProcessor.register(it)
            it.register(ClassBalanceConfiguration::class.java)
            it.register(SkillBalanceConfiguration::class.java)
            it.refresh()
        }
        val classProps = ctx.getBean(ClassDefinitionProperties::class.java)
        val skillProps = ctx.getBean(SkillDefinitionProperties::class.java)
        skills = SkillLookupImpl(skillProps)
        lookup = ClassDefinitionLookup(classProps)
        validator = ClassValidator(classProps, skills)
    }

    @AfterAll
    fun closeContext() {
        ctx.close()
    }

    @Test
    fun `classes_yaml binds every AgentClass entry (8 base + 24 evolutions)`() {
        val all = lookup.all()
        assertEquals(AgentClass.entries.size, all.size)
        assertEquals(AgentClass.entries.toSet(), all.map { it.id }.toSet())
    }

    @Test
    fun `baseClasses returns exactly the 8 v1 base classes`() {
        val bases = lookup.baseClasses()
        assertEquals(8, bases.size)
        assertTrue(bases.all { it.parentClass == null })
        assertEquals(
            setOf(
                AgentClass.SOLDIER, AgentClass.SCOUT, AgentClass.HUNTER, AgentClass.ARTISAN,
                AgentClass.ENGINEER, AgentClass.MEDIC, AgentClass.MERCHANT, AgentClass.RESEARCHER,
            ),
            bases.map { it.id }.toSet(),
        )
    }

    @Test
    fun `every base class declares exactly 3 evolutions and they all back-link`() {
        for (base in lookup.baseClasses()) {
            assertEquals(3, base.evolutions.size, "${base.id} must declare 3 evolutions")
            for (evo in base.evolutions) {
                val def = assertNotNull(lookup.byId(evo), "$evo missing from catalog")
                assertEquals(base.id, def.parentClass, "$evo must back-link to ${base.id}")
            }
        }
    }

    @Test
    fun `every class has populated metadata`() {
        for (def in lookup.all()) {
            assertTrue(def.displayName.isNotBlank(), "${def.id} must declare display-name")
            assertTrue(def.description.isNotBlank(), "${def.id} must declare description")
            assertTrue(def.sightRange > 0, "${def.id} sight-range must be > 0")
            assertTrue(def.primarySkills.isNotEmpty(), "${def.id} must list at least one primary skill")
            assertTrue(def.behaviorFingerprint.isNotEmpty(), "${def.id} must declare a behavior fingerprint")
            assertTrue(def.damageMultipliers.isNotEmpty(), "${def.id} must declare at least one damage multiplier")
        }
    }

    @Test
    fun `FIREARMS hard-ban is carried by RESEARCHER and all its evolutions, nothing else`() {
        val researcherFamily = setOf(
            AgentClass.RESEARCHER, AgentClass.SCHOLAR, AgentClass.ALCHEMIST, AgentClass.NATURALIST,
        )

        for (id in researcherFamily) {
            val def = assertNotNull(lookup.byId(id))
            assertTrue(SkillId("FIREARMS") in def.forbiddenCombatSkills, "$id must carry FIREARMS forbid")
        }

        for (id in AgentClass.entries) {
            if (id in researcherFamily) continue
            val def = assertNotNull(lookup.byId(id))
            assertTrue(
                def.forbiddenCombatSkills.isEmpty(),
                "$id should not declare hard restrictions in v1 (only the Researcher family does)",
            )
        }
    }

    @Test
    fun `skill-XP multiplier follows the 1_5 1_0 0_5 ladder`() {
        val soldier = assertNotNull(lookup.byId(AgentClass.SOLDIER))
        val primary = soldier.primarySkills.first()
        val neutral = soldier.neutralSkills.first()
        val offBuild = SkillId("MEDICINE")

        assertEquals(1.5, lookup.skillXpMultiplier(AgentClass.SOLDIER, primary))
        assertEquals(1.0, lookup.skillXpMultiplier(AgentClass.SOLDIER, neutral))
        assertEquals(0.5, lookup.skillXpMultiplier(AgentClass.SOLDIER, offBuild))
    }

    @Test
    fun `null classId returns 1_0 multipliers and false for restrictions`() {
        assertEquals(1.0, lookup.skillXpMultiplier(null, SkillId("SWORD")))
        assertEquals(1.0, lookup.damageMultiplier(null, "SLASH"))
        assertEquals(false, lookup.forbidsCombatSkill(null, SkillId("FIREARMS")))
    }

    @Test
    fun `production catalog passes the player-side validator`() {
        validator.validate()
    }
}
