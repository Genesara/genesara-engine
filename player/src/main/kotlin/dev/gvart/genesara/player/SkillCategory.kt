package dev.gvart.genesara.player

/**
 * Coarse grouping of skills for UI display. Not load-bearing for any mechanic — every
 * skill behaves the same regardless of category. Adding a new category is a YAML +
 * enum-add change with no schema impact.
 */
enum class SkillCategory {
    GATHERING,
    SURVIVAL,
    COMBAT,
    CRAFTING,
}
