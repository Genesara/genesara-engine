package dev.gvart.genesara.world

import dev.gvart.genesara.player.SkillId
import java.util.UUID

/**
 * One row of [BuildingBarsStore]: per-skill construction progress for a building
 * instance. Every under-construction building owns at least one bar; multi-skill
 * Tier-2 buildings own two or more, each advanced independently when the lead
 * agent calls `build(type, skill)` with the matching skill.
 *
 * Aggregate progress across bars lives on the parent `node_buildings` row; this
 * type carries the per-bar slice.
 */
data class BuildingBar(
    val instanceId: UUID,
    val skill: SkillId,
    val progressSteps: Int,
    val totalSteps: Int,
) {
    val isFilled: Boolean get() = progressSteps >= totalSteps
}
