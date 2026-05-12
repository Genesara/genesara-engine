package dev.gvart.genesara.player

/**
 * An entry from the skill catalog (`player-definition/skills/<category>.yaml`).
 *
 * Skills are agent characteristics: every agent has implicit XP=0 in every skill, but
 * only skills currently in a slot accrue XP from related actions. Slots are
 * permanent — once assigned, a skill stays in its slot for the life of the agent.
 */
data class Skill(
    val id: SkillId,
    val displayName: String,
    val description: String,
    val category: SkillCategory,
    /** Null when the skill declares no passive scaling rule. */
    val levelEffect: LevelEffect? = null,
    /** Non-null = only agents with this classId can slot the skill (caller surfaces SkillNotDiscovered to keep the catalog hidden). */
    val classLock: AgentClass? = null,
)

/** `perLevelPct` is a fractional bonus per level (0.005 = +0.5%/level). */
data class LevelEffect(
    val type: ScalingEffect,
    val perLevelPct: Double,
)
