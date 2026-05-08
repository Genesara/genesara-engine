package dev.gvart.genesara.player

/** One concrete perk a skill offers at a given milestone. */
data class Perk(
    val id: PerkId,
    val skill: SkillId,
    val milestoneLevel: Int,
    val displayName: String,
    val description: String,
    val effect: PerkEffect,
)

/** Binary fork (always exactly two [options]; [PerksValidator] enforces the count). */
data class PerkChoice(
    val skill: SkillId,
    val milestoneLevel: Int,
    val options: List<Perk>,
)
