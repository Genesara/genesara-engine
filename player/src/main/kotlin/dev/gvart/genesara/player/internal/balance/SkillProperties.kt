package dev.gvart.genesara.player.internal.balance

import dev.gvart.genesara.player.SkillCategory

internal data class SkillProperties(
    val displayName: String = "",
    val description: String = "",
    val category: SkillCategory = SkillCategory.SURVIVAL,
)
