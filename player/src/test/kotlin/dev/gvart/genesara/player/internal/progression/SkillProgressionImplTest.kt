package dev.gvart.genesara.player.internal.progression

import dev.gvart.genesara.account.PlayerId
import dev.gvart.genesara.player.AddXpResult
import dev.gvart.genesara.player.Agent
import dev.gvart.genesara.player.AgentClass
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.player.AgentSkillState
import dev.gvart.genesara.player.AgentSkillsRegistry
import dev.gvart.genesara.player.AgentSkillsSnapshot
import dev.gvart.genesara.player.ClassDefinition
import dev.gvart.genesara.player.ClassLookup
import dev.gvart.genesara.player.Perk
import dev.gvart.genesara.player.PerkChoice
import dev.gvart.genesara.player.PerkEffect
import dev.gvart.genesara.player.ScalingEffect
import dev.gvart.genesara.player.PerkId
import dev.gvart.genesara.player.PerkLookup
import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.player.SkillSlotError
import dev.gvart.genesara.player.events.AgentEvent
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SkillProgressionImplTest {

    private val agent = AgentId(UUID.randomUUID())
    private val skill = SkillId("LUMBERJACKING")
    private val commandId = UUID.randomUUID()

    private fun progression(
        skills: AgentSkillsRegistry,
        publisher: ApplicationEventPublisher,
        perks: PerkLookup = StubPerkLookup(),
        agents: AgentRegistry = StubAgents(null),
        classes: ClassLookup = StubClasses(),
    ) = SkillProgressionImpl(skills, perks, agents, classes, publisher)

    @Test
    fun `slotted skill addXp with no milestone fires no events`() {
        val skills = StubSkillsRegistry().apply { slot(skill) }
        val publisher = RecordingPublisher()

        progression(skills, publisher).accrueXp(agent, skill, delta = 1, tick = 7, commandId = commandId)

        assertEquals(listOf(skill to 1), skills.xpAddCalls)
        assertTrue(publisher.events.isEmpty())
    }

    @Test
    fun `slotted skill crossing one milestone publishes one SkillMilestoneReached`() {
        val skills = StubSkillsRegistry().apply {
            slot(skill)
            crossedMilestonesOnNextAdd[skill] = listOf(50)
        }
        val publisher = RecordingPublisher()

        progression(skills, publisher).accrueXp(agent, skill, delta = 5, tick = 11, commandId = commandId)

        val event = publisher.events.filterIsInstance<AgentEvent.SkillMilestoneReached>().single()
        assertEquals(agent, event.agent)
        assertEquals(skill, event.skill)
        assertEquals(50, event.milestone)
        assertEquals(11L, event.tick)
        assertEquals(commandId, event.causedBy)
        assertEquals(listOf(skill to 5), skills.xpAddCalls)
    }

    @Test
    fun `slotted skill crossing multiple milestones in one accrual emits one event per milestone`() {
        val skills = StubSkillsRegistry().apply {
            slot(skill)
            crossedMilestonesOnNextAdd[skill] = listOf(50, 100)
        }
        val publisher = RecordingPublisher()

        progression(skills, publisher).accrueXp(agent, skill, delta = 60, tick = 1, commandId = commandId)

        val milestones = publisher.events.filterIsInstance<AgentEvent.SkillMilestoneReached>().map { it.milestone }
        assertEquals(listOf(50, 100), milestones)
    }

    @Test
    fun `crossed milestone with catalog perks emits PerkChoiceOffered carrying option ids`() {
        val sword = SkillId("SWORD")
        val skills = StubSkillsRegistry().apply {
            slot(sword)
            crossedMilestonesOnNextAdd[sword] = listOf(50)
        }
        val publisher = RecordingPublisher()
        val bleeder = stubPerk("SWORD_BLEEDER", sword, 50)
        val sharpen = stubPerk("SWORD_SHARPEN_EDGE", sword, 50)
        val perks = StubPerkLookup(listOf(bleeder, sharpen))

        progression(skills, publisher, perks).accrueXp(agent, sword, delta = 5, tick = 9, commandId = commandId)

        val emittedTypes = publisher.events.map { it::class.simpleName }
        assertEquals(listOf("SkillMilestoneReached", "PerkChoiceOffered"), emittedTypes)
        val offer = publisher.events.filterIsInstance<AgentEvent.PerkChoiceOffered>().single()
        assertEquals(agent, offer.agent)
        assertEquals(sword, offer.skill)
        assertEquals(50, offer.milestone)
        assertEquals(listOf(bleeder.id, sharpen.id), offer.options)
        assertEquals(9L, offer.tick)
        assertEquals(commandId, offer.causedBy)
    }

    @Test
    fun `crossed milestone without catalog perks emits only SkillMilestoneReached`() {
        val skills = StubSkillsRegistry().apply {
            slot(skill)
            crossedMilestonesOnNextAdd[skill] = listOf(50)
        }
        val publisher = RecordingPublisher()

        progression(skills, publisher).accrueXp(agent, skill, delta = 5, tick = 7, commandId = commandId)

        assertTrue(publisher.events.none { it is AgentEvent.PerkChoiceOffered })
        assertEquals(1, publisher.events.filterIsInstance<AgentEvent.SkillMilestoneReached>().size)
    }

    @Test
    fun `unslotted skill with maybeRecommend value emits SkillRecommended carrying snapshot slotsFree`() {
        val skills = StubSkillsRegistry().apply {
            recommendOnNext[skill] = 2
            slotCount = 8
            slotsFilled = 3
        }
        val publisher = RecordingPublisher()

        progression(skills, publisher).accrueXp(agent, skill, delta = 1, tick = 4, commandId = commandId)

        val rec = publisher.events.filterIsInstance<AgentEvent.SkillRecommended>().single()
        assertEquals(agent, rec.agent)
        assertEquals(skill, rec.skill)
        assertEquals(2, rec.recommendCount)
        assertEquals(5, rec.slotsFree)
        assertEquals(4L, rec.tick)
        assertEquals(commandId, rec.causedBy)
        assertTrue(skills.xpAddCalls.isEmpty())
    }

    @Test
    fun `unslotted skill with maybeRecommend null emits no event`() {
        val skills = StubSkillsRegistry()
        val publisher = RecordingPublisher()

        progression(skills, publisher).accrueXp(agent, skill, delta = 1, tick = 1, commandId = commandId)

        assertTrue(publisher.events.isEmpty())
        assertEquals(listOf(skill to 1L), skills.recommendCalls)
    }

    @Test
    fun `class XP multiplier scales delta on the addXp call`() {
        val skills = StubSkillsRegistry().apply { slot(skill) }
        val publisher = RecordingPublisher()
        val agents = StubAgents(AgentClass.HUNTER)
        val classes = StubClasses(mapOf((AgentClass.HUNTER to skill) to 1.5))

        progression(skills, publisher, agents = agents, classes = classes)
            .accrueXp(agent, skill, delta = 10, tick = 1, commandId = commandId)

        assertEquals(listOf(skill to 15), skills.xpAddCalls)
    }

    @Test
    fun `off-build skill is scaled to half delta with at least 1 floor`() {
        val skills = StubSkillsRegistry().apply { slot(skill) }
        val publisher = RecordingPublisher()
        val agents = StubAgents(AgentClass.HUNTER)
        val classes = StubClasses(mapOf((AgentClass.HUNTER to skill) to 0.5))

        progression(skills, publisher, agents = agents, classes = classes)
            .accrueXp(agent, skill, delta = 1, tick = 1, commandId = commandId)

        assertEquals(listOf(skill to 1), skills.xpAddCalls, "non-positive multiplier-rounded delta clamps to 1")
    }

    @Test
    fun `unclassed agent receives the unscaled delta`() {
        val skills = StubSkillsRegistry().apply { slot(skill) }
        val publisher = RecordingPublisher()

        progression(skills, publisher, agents = StubAgents(null))
            .accrueXp(agent, skill, delta = 7, tick = 1, commandId = commandId)

        assertEquals(listOf(skill to 7), skills.xpAddCalls)
    }

    @Test
    fun `slotted skill never triggers maybeRecommend even when it's scripted`() {
        val skills = StubSkillsRegistry().apply {
            slot(skill)
            recommendOnNext[skill] = 1
        }
        val publisher = RecordingPublisher()

        progression(skills, publisher).accrueXp(agent, skill, delta = 1, tick = 1, commandId = commandId)

        assertTrue(publisher.events.none { it is AgentEvent.SkillRecommended })
        assertTrue(skills.recommendCalls.isEmpty())
    }

    private fun stubPerk(id: String, skill: SkillId, milestoneLevel: Int) = Perk(
        id = PerkId(id),
        skill = skill,
        milestoneLevel = milestoneLevel,
        displayName = id,
        description = id,
        effect = PerkEffect.PassiveAura(target = ScalingEffect.SLASH_DAMAGE_BONUS, magnitude = 1),
    )

    private class StubSkillsRegistry : AgentSkillsRegistry {
        private val slottedSkills = mutableSetOf<SkillId>()
        val xpAddCalls = mutableListOf<Pair<SkillId, Int>>()
        val recommendCalls = mutableListOf<Pair<SkillId, Long>>()
        val crossedMilestonesOnNextAdd = mutableMapOf<SkillId, List<Int>>()
        val recommendOnNext = mutableMapOf<SkillId, Int?>()
        var slotCount: Int = 8
        var slotsFilled: Int = 0

        fun slot(skill: SkillId) {
            slottedSkills += skill
            slotsFilled = slottedSkills.size
        }

        override fun snapshot(agent: AgentId): AgentSkillsSnapshot =
            AgentSkillsSnapshot(
                perSkill = slottedSkills.associateWith { skillId ->
                    AgentSkillState(
                        skill = skillId,
                        xp = 0,
                        level = 0,
                        slotIndex = slottedSkills.indexOf(skillId),
                        recommendCount = 0,
                    )
                }.mapKeys { it.key },
                slotCount = slotCount,
                slotsFilled = slotsFilled,
            )

        override fun addXpIfSlotted(agent: AgentId, skill: SkillId, delta: Int): AddXpResult {
            if (skill !in slottedSkills) return AddXpResult.Unslotted
            xpAddCalls += skill to delta
            val crossed = crossedMilestonesOnNextAdd.remove(skill) ?: emptyList()
            return AddXpResult.Accrued(crossed)
        }

        override fun maybeRecommend(agent: AgentId, skill: SkillId, tick: Long): Int? {
            if (skill in slottedSkills) return null
            recommendCalls += skill to tick
            return recommendOnNext.remove(skill)
        }

        override fun setSlot(agent: AgentId, skill: SkillId, slotIndex: Int): SkillSlotError? {
            slottedSkills += skill
            slotsFilled = slottedSkills.size
            return null
        }
    }

    private class RecordingPublisher : ApplicationEventPublisher {
        val events = mutableListOf<Any>()
        override fun publishEvent(event: Any) {
            events += event
        }
    }

    private class StubAgents(private val classId: AgentClass?) : AgentRegistry {
        override fun find(id: AgentId): Agent? = Agent(
            id = id,
            owner = PlayerId(UUID.randomUUID()),
            name = "stub",
            classId = classId,
        )
        override fun listForOwner(owner: PlayerId): List<Agent> = emptyList()
    }

    private class StubClasses(
        private val multipliers: Map<Pair<AgentClass, SkillId>, Double> = emptyMap(),
    ) : ClassLookup {
        override fun byId(classId: AgentClass): ClassDefinition? = null
        override fun all(): List<ClassDefinition> = emptyList()
        override fun baseClasses(): List<ClassDefinition> = emptyList()
        override fun evolutionsOf(parent: AgentClass): List<ClassDefinition> = emptyList()
        override fun sightRange(classId: AgentClass?): Int = 3
        override fun skillXpMultiplier(classId: AgentClass?, skill: SkillId): Double {
            if (classId == null) return 1.0
            return multipliers[classId to skill] ?: 1.0
        }
        override fun damageMultiplier(classId: AgentClass?, damageType: String): Double = 1.0
        override fun forbidsCombatSkill(classId: AgentClass?, combatSkill: SkillId): Boolean = false
    }

    private class StubPerkLookup(private val perks: List<Perk> = emptyList()) : PerkLookup {
        private val byId = perks.associateBy { it.id }
        override fun byId(id: PerkId): Perk? = byId[id]
        override fun choicesAt(skill: SkillId, milestoneLevel: Int): PerkChoice? {
            val opts = perks.filter { it.skill == skill && it.milestoneLevel == milestoneLevel }
            return if (opts.isEmpty()) null else PerkChoice(skill, milestoneLevel, opts)
        }
        override fun choicesFor(skill: SkillId): List<PerkChoice> =
            perks.filter { it.skill == skill }
                .groupBy { it.milestoneLevel }
                .toSortedMap()
                .map { (level, list) -> PerkChoice(skill, level, list) }
        override fun all(): List<Perk> = perks
    }
}
