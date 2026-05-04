package dev.gvart.genesara.world.internal.progression

import dev.gvart.genesara.player.AddXpResult
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentSkillsRegistry
import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.world.events.WorldEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Owns the "grant XP, fan out milestone or recommendation events" rule used by every
 * skill-emitting reducer (harvest, build, craft, …).
 */
@Component
internal class SkillProgression(
    private val skills: AgentSkillsRegistry,
    private val publisher: ApplicationEventPublisher,
) {

    /**
     * Grant [delta] XP toward [skill] for [agent]. If the skill is slotted, fans out one
     * [WorldEvent.SkillMilestoneReached] per crossed milestone; if it is unslotted and
     * [AgentSkillsRegistry.maybeRecommend] decides to fire, emits a single
     * [WorldEvent.SkillRecommended]. Both event types tag [commandId] as `causedBy` so
     * agents can correlate.
     */
    fun accrueXp(
        agent: AgentId,
        skill: SkillId,
        delta: Int,
        tick: Long,
        commandId: UUID,
    ) {
        when (val result = skills.addXpIfSlotted(agent, skill, delta)) {
            is AddXpResult.Accrued -> result.crossedMilestones.forEach { milestone ->
                publisher.publishEvent(
                    WorldEvent.SkillMilestoneReached(
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
                    WorldEvent.SkillRecommended(
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
