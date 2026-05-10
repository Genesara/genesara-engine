package dev.gvart.genesara.player.internal.progression

import dev.gvart.genesara.player.AddXpResult
import dev.gvart.genesara.player.AgentClass
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.player.AgentSkillsRegistry
import dev.gvart.genesara.player.ClassLookup
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
    private val agents: AgentRegistry,
    private val classes: ClassLookup,
    private val publisher: ApplicationEventPublisher,
) : SkillProgression {

    override fun accrueXp(
        agent: AgentId,
        skill: SkillId,
        delta: Int,
        tick: Long,
        commandId: UUID,
        classId: AgentClass?,
    ) {
        val scaledDelta = scaleByClass(agent, skill, delta, classId)
        when (val result = skills.addXpIfSlotted(agent, skill, scaledDelta)) {
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

    /**
     * Apply the per-class soft XP modifier (1.5 / 1.0 / 0.5) declared in
     * `classes.yaml`. Pre-level-10 agents have no class and pass through at
     * 1.0x. A non-positive [delta] short-circuits the registry read — the
     * downstream registry is the canonical clamp anyway.
     *
     * The `coerceAtLeast(1)` floor keeps a single-XP off-build grant from
     * silently rounding to 0; documented behavior, not a bug — agents who
     * crossed the discovery gate (recommendation) deserve to see their xp
     * counter tick even on off-build skills.
     */
    private fun scaleByClass(agent: AgentId, skill: SkillId, delta: Int, classId: AgentClass?): Int {
        if (delta <= 0) return delta
        val resolved = classId ?: agents.find(agent)?.classId ?: return delta
        val multiplier = classes.skillXpMultiplier(resolved, skill)
        return (delta * multiplier).toInt().coerceAtLeast(1)
    }
}
