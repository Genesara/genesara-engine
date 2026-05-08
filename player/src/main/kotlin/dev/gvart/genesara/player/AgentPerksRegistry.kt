package dev.gvart.genesara.player

/**
 * Read + write surface for per-agent perk choices.
 *
 * Choices are forever (no `clearChoice`); one perk per (agent, skill, milestone) is
 * enforced by the DB PK and surfaced as a typed rejection via [RecordPerkResult].
 */
interface AgentPerksRegistry {

    fun snapshot(agent: AgentId): AgentPerksSnapshot

    /** Skill + milestone are derived from the catalog entry, so callers cannot mismatch them. */
    fun recordChoice(agent: AgentId, perk: PerkId, tick: Long): RecordPerkResult
}

data class AgentPerksSnapshot(val perks: List<AgentPerk>)

data class AgentPerk(
    val skill: SkillId,
    val milestoneLevel: Int,
    val perkId: PerkId,
    val chosenAtTick: Long,
)

sealed interface RecordPerkResult {
    data object Recorded : RecordPerkResult
    data class UnknownPerk(val perk: PerkId) : RecordPerkResult
    data class MilestoneAlreadyChosen(
        val skill: SkillId,
        val milestoneLevel: Int,
        val existing: PerkId,
    ) : RecordPerkResult
}
