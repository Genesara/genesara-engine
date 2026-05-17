package dev.gvart.genesara.world.internal.balance

import dev.gvart.genesara.player.SkillLookup
import dev.gvart.genesara.world.ItemLookup
import jakarta.annotation.PostConstruct
import org.springframework.stereotype.Component

/**
 * Cross-validates `terrains.yaml` and `items.yaml` against sibling catalogs at startup.
 * Fails fast on misconfiguration so the runtime hot path stays branch-free. Catches:
 * spawn-rule items missing from `items.yaml` (the enum value exists but no catalog
 * entry backs it), malformed quantity ranges, out-of-bounds spawn chances, unknown
 * harvest-skill references (XP grants would silently no-op), unknown combat-skill
 * references (same risk on the attack hook), and unknown required-skills keys
 * (items would be permanently un-equippable).
 *
 * Typos in the YAML `item:` field itself are caught earlier — the Spring binder
 * rejects anything not in [dev.gvart.genesara.world.ResourceItemId] before this
 * validator runs.
 */
@Component
internal class ResourceSpawnsValidator(
    private val world: WorldDefinitionProperties,
    private val items: ItemLookup,
    private val skills: SkillLookup,
) {

    @PostConstruct
    fun validate() {
        val knownIds = items.all().map { it.id.value }.toSet()
        val problems = mutableListOf<String>()

        problems += spawnRuleProblems(knownIds)
        problems += unknownHarvestSkillProblems()
        problems += unknownCombatSkillProblems()
        problems += unknownRequiredSkillProblems()

        require(problems.isEmpty()) {
            buildString {
                append("Resource spawn rules failed validation:\n")
                problems.forEach { appendLine(it) }
                append("Known item ids: $knownIds")
            }
        }
    }

    private fun spawnRuleProblems(knownIds: Set<String>): List<String> {
        val problems = mutableListOf<String>()
        for ((terrain, terrainProps) in world.terrains) {
            for ((index, rule) in terrainProps.resourceSpawns.withIndex()) {
                val itemName = rule.item?.name
                if (itemName == null) {
                    problems += "  $terrain[#$index] item is missing"
                } else if (itemName !in knownIds) {
                    problems += "  $terrain[#$index] item='$itemName' is not in the catalog"
                }
                if (rule.quantityRange.size != 2) {
                    problems += "  $terrain[#$index] quantity-range must have exactly 2 elements; got ${rule.quantityRange}"
                } else {
                    val lo = rule.quantityRange[0]
                    val hi = rule.quantityRange[1]
                    if (lo < 0 || hi < 0) {
                        problems += "  $terrain[#$index] quantity-range must be non-negative; got $lo..$hi"
                    } else if (lo > hi) {
                        problems += "  $terrain[#$index] quantity-range min > max ($lo > $hi)"
                    }
                }
                if (rule.spawnChance !in 0.0..1.0) {
                    problems += "  $terrain[#$index] spawn-chance must be in [0.0, 1.0]; got ${rule.spawnChance}"
                }
            }
        }
        return problems
    }

    private fun unknownHarvestSkillProblems(): List<String> =
        items.all().mapNotNull { item ->
            val skill = item.harvestSkill ?: return@mapNotNull null
            if (skills.byId(skill) == null) {
                "  item ${item.id.value} declares harvest-skill='${skill.value}' which is not in the skill catalog"
            } else null
        }

    private fun unknownCombatSkillProblems(): List<String> =
        items.all().mapNotNull { item ->
            val skill = item.combatSkill ?: return@mapNotNull null
            if (skills.byId(skill) == null) {
                "  item ${item.id.value} declares combat-skill='${skill.value}' which is not in the skill catalog"
            } else null
        }

    private fun unknownRequiredSkillProblems(): List<String> =
        items.all().flatMap { item ->
            item.requiredSkills.keys.mapNotNull { skillId ->
                if (skills.byId(skillId) == null) {
                    "  item ${item.id.value} declares required-skills key '${skillId.value}' which is not in the skill catalog"
                } else null
            }
        }
}
