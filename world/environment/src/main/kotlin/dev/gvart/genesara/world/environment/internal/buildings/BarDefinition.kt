package dev.gvart.genesara.world.environment.internal.buildings

import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.world.ItemId

data class BarDefinition(
    val skill: SkillId,
    val level: Int,
    val steps: Int,
    val materialsPerStep: Map<ItemId, Int>,
)
