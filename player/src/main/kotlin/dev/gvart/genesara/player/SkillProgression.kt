package dev.gvart.genesara.player

import dev.gvart.genesara.player.events.AgentEvent
import dev.gvart.genesara.player.internal.progression.SkillProgressionImpl
import org.springframework.context.ApplicationEventPublisher
import java.util.UUID

/**
 * Owns the "grant XP, fan out milestone or recommendation events" rule used by every
 * skill-emitting reducer (harvest, build, craft, …).
 */
interface SkillProgression {

    /**
     * Grant [delta] XP toward [skill] for [agent]. If the skill is slotted, fans out one
     * [AgentEvent.SkillMilestoneReached] per crossed milestone; if it is unslotted and
     * [AgentSkillsRegistry.maybeRecommend] decides to fire, emits a single
     * [AgentEvent.SkillRecommended]. Both event types tag [commandId] as `causedBy` so
     * agents can correlate.
     */
    fun accrueXp(
        agent: AgentId,
        skill: SkillId,
        delta: Int,
        tick: Long,
        commandId: UUID,
    )
}

/**
 * Fake-constructor for tests that want a real publishing instance without reaching
 * into [SkillProgressionImpl]'s `internal/` package. Production code receives the
 * Spring-managed [SkillProgressionImpl] bean by interface type.
 *
 * [perks] defaults to an empty catalog so reducer tests that don't care about perks
 * keep their existing call sites; pass a real [PerkLookup] when the test asserts on
 * `PerkChoiceOffered` emission.
 */
@Suppress("FunctionName")
fun SkillProgression(
    skills: AgentSkillsRegistry,
    publisher: ApplicationEventPublisher,
    perks: PerkLookup = NoPerks,
): SkillProgression = SkillProgressionImpl(skills, perks, publisher)

private object NoPerks : PerkLookup {
    override fun byId(id: PerkId): Perk? = null
    override fun choicesAt(skill: SkillId, milestoneLevel: Int): PerkChoice? = null
    override fun choicesFor(skill: SkillId): List<PerkChoice> = emptyList()
    override fun all(): List<Perk> = emptyList()
}