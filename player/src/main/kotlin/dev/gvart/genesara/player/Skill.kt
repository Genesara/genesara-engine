package dev.gvart.genesara.player

/**
 * An entry from the skill catalog (`player-definition/skills.yaml`).
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
)
