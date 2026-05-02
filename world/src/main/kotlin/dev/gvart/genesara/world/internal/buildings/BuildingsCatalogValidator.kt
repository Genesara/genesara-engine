package dev.gvart.genesara.world.internal.buildings

import dev.gvart.genesara.world.BuildingType
import dev.gvart.genesara.world.ItemLookup
import jakarta.annotation.PostConstruct
import org.springframework.stereotype.Component

/**
 * Cross-validates `buildings.yaml` at startup. Mirrors [dev.gvart.genesara.world.internal.balance.ConsumablesValidator]:
 * fail fast with one error message naming every offending entry.
 */
@Component
internal class BuildingsCatalogValidator(
    private val catalog: BuildingsCatalog,
    private val items: ItemLookup,
) {

    @PostConstruct
    fun validate() {
        val problems = mutableListOf<String>()

        val present = catalog.all().map { it.type }.toSet()
        val missing = BuildingType.entries.filter { it !in present }
        if (missing.isNotEmpty()) {
            problems += "Missing catalog entries for: ${missing.joinToString { it.name }}"
        }

        for (def in catalog.all()) {
            // totalSteps >= 2: a 1-step building completes on the first call, defeating
            // the active-loop design (project memory feedback_active_agent_loop). The
            // schema CHECK that pins UNDER_CONSTRUCTION ↔ progress<total also makes the
            // (insert-then-complete-same-tick) path unsafe for 1-step rows.
            if (def.totalSteps < 2) problems += "${def.type}: totalSteps must be >= 2 (got ${def.totalSteps})"
            if (def.staminaPerStep <= 0) problems += "${def.type}: staminaPerStep must be > 0 (got ${def.staminaPerStep})"
            if (def.hp <= 0) problems += "${def.type}: hp must be > 0 (got ${def.hp})"
            if (def.requiredSkillLevel < 0) problems += "${def.type}: requiredSkillLevel must be >= 0 (got ${def.requiredSkillLevel})"

            for ((itemId, total) in def.totalMaterials) {
                if (total <= 0) problems += "${def.type}: material ${itemId.value} total must be > 0 (got $total)"
                if (items.byId(itemId) == null) {
                    problems += "${def.type}: material ${itemId.value} is not in the items catalog"
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
