package dev.gvart.genesara.player

import dev.gvart.genesara.account.PlayerId
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
     *
     * Pass [classId] when the caller already has the agent row in scope (every
     * combat / harvest / craft reducer does) — that skips the `AgentRegistry.find`
     * round-trip the soft-XP scaler would otherwise pay on every grant. A null
     * value is the legacy fallback: the impl falls back to `agents.find(agent)`.
     */
    fun accrueXp(
        agent: AgentId,
        skill: SkillId,
        delta: Int,
        tick: Long,
        commandId: UUID,
        classId: AgentClass? = null,
    )
}

/**
 * Fake-constructor for tests that want a real publishing instance without reaching
 * into [SkillProgressionImpl]'s `internal/` package. Production code receives the
 * Spring-managed [SkillProgressionImpl] bean by interface type.
 *
 * [perks] defaults to an empty catalog so reducer tests that don't care about perks
 * keep their existing call sites; pass a real [PerkLookup] when the test asserts on
 * `PerkChoiceOffered` emission. [agents] and [classes] default to no-op stubs so
 * tests that aren't asserting class-driven XP scaling get the previous unscaled
 * (1.0x) behavior automatically.
 */
@Suppress("FunctionName")
fun SkillProgression(
    skills: AgentSkillsRegistry,
    publisher: ApplicationEventPublisher,
    perks: PerkLookup = NoPerks,
    agents: AgentRegistry = NoAgents,
    classes: ClassLookup = NoClasses,
): SkillProgression = SkillProgressionImpl(skills, perks, agents, classes, publisher)

private object NoPerks : PerkLookup {
    override fun byId(id: PerkId): Perk? = null
    override fun choicesAt(skill: SkillId, milestoneLevel: Int): PerkChoice? = null
    override fun choicesFor(skill: SkillId): List<PerkChoice> = emptyList()
    override fun all(): List<Perk> = emptyList()
}

private object NoAgents : AgentRegistry {
    override fun find(id: AgentId): Agent? = null
    override fun listForOwner(owner: PlayerId): List<Agent> = emptyList()
}

private val NoClasses: ClassLookup = NoOpClassLookup