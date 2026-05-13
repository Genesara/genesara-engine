package dev.gvart.genesara.player.internal.balance

import dev.gvart.genesara.player.AbilityCostResource
import dev.gvart.genesara.player.AgentClass
import dev.gvart.genesara.player.PerkEffect
import dev.gvart.genesara.player.SkillCategory
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
class SkillsYamlLoadingTest {

    private lateinit var ctx: AnnotationConfigApplicationContext
    private lateinit var skills: SkillLookupImpl
    private lateinit var perks: PerkLookupImpl

    @BeforeAll
    fun bootContext() {
        ctx = AnnotationConfigApplicationContext().also {
            ConfigurationPropertiesBindingPostProcessor.register(it)
            it.register(SkillBalanceConfiguration::class.java)
            it.refresh()
        }
        val props = ctx.getBean(SkillDefinitionProperties::class.java)
        skills = SkillLookupImpl(props)
        perks = PerkLookupImpl(props)
    }

    @AfterAll
    fun closeContext() {
        ctx.close()
    }

    @Test
    fun `production skills_yaml binds cleanly and ships the v1 catalog shape`() {
        assertEquals(50, skills.all().size, "v1 skill catalog ships 50 entries (10 categories)")

        for (skill in skills.all()) {
            assertNotNull(skill.levelEffect, "${skill.id.value} must declare a levelEffect")
            assertTrue(
                skill.displayName.isNotBlank(),
                "${skill.id.value} must have a display-name",
            )
            assertTrue(
                skill.description.isNotBlank(),
                "${skill.id.value} must have a description",
            )
        }

        val expectedCategories = SkillCategory.entries.toSet()
        val seenCategories = skills.all().map { it.category }.toSet()
        assertEquals(expectedCategories, seenCategories, "all 10 categories present")
    }

    @Test
    fun `SCANNING is class-locked to RESEARCHER`() {
        val scanning = assertNotNull(skills.byId(SkillId("SCANNING")))
        assertEquals(SkillCategory.CLASS_LOCKED, scanning.category)
        assertEquals(AgentClass.RESEARCHER, scanning.classLock)

        for (other in skills.all().filter { it.id.value != "SCANNING" }) {
            assertEquals(
                null,
                other.classLock,
                "${other.id.value} should not declare a class-lock — SCANNING is the only one in v1",
            )
        }
    }

    @Test
    fun `DUAL_WIELD has no ActiveAbility perks — passive-only by spec`() {
        val dualWieldPerks = perks.choicesFor(SkillId("DUAL_WIELD")).flatMap { it.options }
        assertTrue(dualWieldPerks.isNotEmpty(), "DUAL_WIELD must declare perks")
        assertTrue(
            dualWieldPerks.none { it.effect is PerkEffect.ActiveAbility },
            "DUAL_WIELD declares ActiveAbility perks; spec forbids them",
        )
    }

    @Test
    fun `every milestone is a 1-of-2 fork at the legal levels`() {
        for (skill in skills.all()) {
            val choices = perks.choicesFor(skill.id)
            assertEquals(
                setOf(50, 100, 150),
                choices.map { it.milestoneLevel }.toSet(),
                "${skill.id.value} must define milestones at exactly 50/100/150",
            )
            for (choice in choices) {
                assertEquals(
                    2,
                    choice.options.size,
                    "${skill.id.value}@${choice.milestoneLevel} must be a 1-of-2 fork",
                )
            }
        }
    }

    @Test
    fun `active-ability resource distribution lands within spec budget`() {
        val actives = perks.all().mapNotNull { it.effect as? PerkEffect.ActiveAbility }
        val byResource = actives.groupingBy { it.costResource }.eachCount()

        val hp = byResource[AbilityCostResource.HP] ?: 0
        val mana = byResource[AbilityCostResource.MANA] ?: 0
        assertTrue(hp in 5..10, "HP-cost actives must be 5..10 (got $hp)")
        assertTrue(mana in 3..5, "Mana-cost actives must be 3..5 (got $mana)")
    }

    @Test
    fun `total perk count is in the design ballpark`() {
        val total = perks.all().size
        assertTrue(
            total in 280..310,
            "v1 catalog targets ~300 perks (49 skills * 6 milestone slots = 294); got $total",
        )
    }
}
