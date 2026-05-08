package dev.gvart.genesara.player.internal.progression

import dev.gvart.genesara.player.AddXpResult
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentSkillsRegistry
import dev.gvart.genesara.player.PerkLookup
import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.player.SkillProgression
import dev.gvart.genesara.player.events.AgentEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import java.util.UUID

@Component
internal class SkillProgressionImpl(
    private val skills: AgentSkillsRegistry,
    private val perks: PerkLookup,
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
                perks.choicesAt(skill, milestone)?.let { choice ->
                    publisher.publishEvent(
                        AgentEvent.PerkChoiceOffered(
                            agent = agent,
                            skill = skill,
                            milestone = milestone,
                            options = choice.options.map { it.id },
                            tick = tick,
                            causedBy = commandId,
                        ),
                    )
                }
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
