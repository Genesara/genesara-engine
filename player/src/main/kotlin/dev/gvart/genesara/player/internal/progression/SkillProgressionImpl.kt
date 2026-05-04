package dev.gvart.genesara.player.internal.progression

import dev.gvart.genesara.player.AddXpResult
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentSkillsRegistry
import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.player.SkillProgression
import dev.gvart.genesara.player.events.AgentEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import java.util.UUID

@Component
internal class SkillProgressionImpl(
    private val skills: AgentSkillsRegistry,
    private val publisher: ApplicationEventPublisher,
) : SkillProgression {

    override fun accrueXp(
        agent: AgentId,
        skill: SkillId,
        delta: Int,
        tick: Long,
        commandId: UUID,
    ) {
        when (val result = skills.addXpIfSlotted(agent, skill, delta)) {
            is AddXpResult.Accrued -> result.crossedMilestones.forEach { milestone ->
                publisher.publishEvent(
                    AgentEvent.SkillMilestoneReached(
                        agent = agent,
                        skill = skill,
                        milestone = milestone,
                        tick = tick,
                        causedBy = commandId,
                    ),
                )
            }
            AddXpResult.Unslotted -> skills.maybeRecommend(agent, skill, tick)?.let { newCount ->
                val snapshot = skills.snapshot(agent)
                publisher.publishEvent(
                    AgentEvent.SkillRecommended(
                        agent = agent,
                        skill = skill,
                        recommendCount = newCount,
                        slotsFree = snapshot.slotCount - snapshot.slotsFilled,
                        tick = tick,
                        causedBy = commandId,
                    ),
                )
            }
        }
    }
}
