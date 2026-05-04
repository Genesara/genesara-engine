package dev.gvart.genesara.player.internal.progression

import dev.gvart.genesara.player.AddXpResult
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentSkillState
import dev.gvart.genesara.player.AgentSkillsRegistry
import dev.gvart.genesara.player.AgentSkillsSnapshot
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

    @Test
    fun `slotted skill addXp with no milestone fires no events`() {
        val skills = StubSkillsRegistry().apply { slot(skill) }
        val publisher = RecordingPublisher()

        SkillProgressionImpl(skills, publisher).accrueXp(agent, skill, delta = 1, tick = 7, commandId = commandId)

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

        SkillProgressionImpl(skills, publisher).accrueXp(agent, skill, delta = 5, tick = 11, commandId = commandId)

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

        SkillProgressionImpl(skills, publisher).accrueXp(agent, skill, delta = 60, tick = 1, commandId = commandId)

        val milestones = publisher.events.filterIsInstance<AgentEvent.SkillMilestoneReached>().map { it.milestone }
        assertEquals(listOf(50, 100), milestones)
    }

    @Test
    fun `unslotted skill with maybeRecommend value emits SkillRecommended carrying snapshot slotsFree`() {
        val skills = StubSkillsRegistry().apply {
            recommendOnNext[skill] = 2
            slotCount = 8
            slotsFilled = 3
        }
        val publisher = RecordingPublisher()

        SkillProgressionImpl(skills, publisher).accrueXp(agent, skill, delta = 1, tick = 4, commandId = commandId)

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

        SkillProgressionImpl(skills, publisher).accrueXp(agent, skill, delta = 1, tick = 1, commandId = commandId)

        assertTrue(publisher.events.isEmpty())
        assertEquals(listOf(skill to 1L), skills.recommendCalls)
    }

    @Test
    fun `slotted skill never triggers maybeRecommend even when it's scripted`() {
        val skills = StubSkillsRegistry().apply {
            slot(skill)
            recommendOnNext[skill] = 1
        }
        val publisher = RecordingPublisher()

        SkillProgressionImpl(skills, publisher).accrueXp(agent, skill, delta = 1, tick = 1, commandId = commandId)

        assertTrue(publisher.events.none { it is AgentEvent.SkillRecommended })
        assertTrue(skills.recommendCalls.isEmpty())
    }

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
}
