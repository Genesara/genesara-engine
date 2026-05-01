package dev.gvart.genesara.world.internal.balance

import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.player.SkillLookup
import dev.gvart.genesara.world.Item
import dev.gvart.genesara.world.ItemLookup
import jakarta.annotation.PostConstruct
import org.springframework.stereotype.Component

/**
 * Cross-validates `terrains.yaml` and `items.yaml` against sibling catalogs at startup.
 * Fails fast on misconfiguration so the runtime hot path stays branch-free. Catches:
 * unknown item ids in spawn rules, malformed quantity ranges, out-of-bounds spawn
 * chances, unknown gathering-skill references (XP grants would silently no-op), and
 * unknown required-skills keys (items would be permanently un-equippable).
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
        problems += unknownGatheringSkillProblems()
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
                if (rule.item !in knownIds) {
                    problems += "  $terrain[#$index] item='${rule.item}' is not in the catalog"
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

    private fun unknownGatheringSkillProblems(): List<String> =
        items.all().mapNotNull { item ->
            val skillId = item.gatheringSkill ?: return@mapNotNull null
            if (skills.byId(SkillId(skillId)) == null) {
                "  item ${item.id.value} declares gathering-skill='$skillId' which is not in the skill catalog"
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
