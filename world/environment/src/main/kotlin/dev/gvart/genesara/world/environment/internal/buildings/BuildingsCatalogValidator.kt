package dev.gvart.genesara.world.environment.internal.buildings

import dev.gvart.genesara.world.BuildingType
import dev.gvart.genesara.world.ItemLookup
import jakarta.annotation.PostConstruct
import org.springframework.stereotype.Component

@Component
internal class BuildingsCatalogValidator(
    private val catalog: BuildingsCatalog,
    private val items: ItemLookup,
) {

    @PostConstruct
    fun validate() {
        val problems = mutableListOf<String>()

        val present = catalog.allDefs().map { it.type }.toSet()
        val missing = BuildingType.entries.filter { it !in present }
        if (missing.isNotEmpty()) {
            problems += "Missing catalog entries for: ${missing.joinToString { it.name }}"
        }

        for (def in catalog.allDefs()) {
            // totalSteps >= 2: a 1-step building completes on the first call, defeating
            // the active-loop design (project memory feedback_active_agent_loop). The
            // schema CHECK that pins UNDER_CONSTRUCTION ↔ progress<total also makes the
            // (insert-then-complete-same-tick) path unsafe for 1-step rows.
            if (def.totalSteps < 2) problems += "${def.type}: totalSteps must be >= 2 (got ${def.totalSteps})"
            if (def.staminaPerStep <= 0) problems += "${def.type}: staminaPerStep must be > 0 (got ${def.staminaPerStep})"
            if (def.hp <= 0) problems += "${def.type}: hp must be > 0 (got ${def.hp})"
            if (def.skillBars.isEmpty()) problems += "${def.type}: must declare at least one skill-bar"
            if (def.sightBlockerHeight < 0) {
                problems += "${def.type}: sightBlockerHeight must be >= 0 (got ${def.sightBlockerHeight})"
            }
            if (def.observerHeightBonus < 0) {
                problems += "${def.type}: observerHeightBonus must be >= 0 (got ${def.observerHeightBonus})"
            }

            for (bar in def.skillBars) {
                if (bar.level < 0) problems += "${def.type}/${bar.skill.value}: level must be >= 0 (got ${bar.level})"
                if (bar.steps < 1) problems += "${def.type}/${bar.skill.value}: steps must be >= 1 (got ${bar.steps})"
                for ((itemId, perStep) in bar.materialsPerStep) {
                    if (perStep <= 0) {
                        problems += "${def.type}/${bar.skill.value}: material ${itemId.value} per-step must be > 0 (got $perStep)"
                    }
                    if (items.byId(itemId) == null) {
                        problems += "${def.type}/${bar.skill.value}: material ${itemId.value} is not in the items catalog"
                    }
                }
            }

            val isChest = def.type == BuildingType.STORAGE_CHEST
            if (isChest && def.chestCapacityGrams == null) {
                problems += "${def.type}: chestCapacityGrams must be set for STORAGE_CHEST"
            }
            if (!isChest && def.chestCapacityGrams != null) {
                problems += "${def.type}: chestCapacityGrams must be null for non-chest types"
            }
        }

        require(problems.isEmpty()) {
            buildString {
                append("Building catalog validation failed:\n")
                problems.forEach { append("  - $it\n") }
            }
        }
    }
}
