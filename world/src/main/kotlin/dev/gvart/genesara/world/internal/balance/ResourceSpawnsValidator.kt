package dev.gvart.genesara.world.internal.balance

import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.player.SkillLookup
import dev.gvart.genesara.world.ItemLookup
import jakarta.annotation.PostConstruct
import org.springframework.stereotype.Component

/**
 * Cross-validates `terrains.yaml` and `items.yaml` against sibling catalogs at startup.
 * Four classes of misconfiguration are checked:
 *
 *  1. **Unknown item id** in a spawn rule (typo in YAML).
 *  2. **Malformed quantity range** — must be `[min, max]` with `min <= max` and both
 *     non-negative. Spring's binder happily accepts any list, so we enforce shape here.
 *  3. **Out-of-bounds spawn chance** — must be in `[0.0, 1.0]`.
 *  4. **Unknown gathering-skill** — every `gathering-skill` on an item must reference
 *     a skill that exists in `:player`'s skill catalog. Otherwise the gather-XP grant
 *     would silently no-op forever.
 *
 * Fails fast on misconfiguration. Keep validation here (not in the lookups) so the
 * runtime hot path stays branch-free.
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

        // Cross-check item.gatheringSkill against the player-side skill catalog. An
        // item can only declare a skill that actually exists; otherwise XP grants
        // would be silently dropped at runtime.
        for (item in items.all()) {
            val skillId = item.gatheringSkill ?: continue
            if (skills.byId(SkillId(skillId)) == null) {
                problems += "  item ${item.id.value} declares gathering-skill='$skillId' which is not in the skill catalog"
            }
        }

        require(problems.isEmpty()) {
            buildString {
                append("Resource spawn rules failed validation:\n")
                problems.forEach { appendLine(it) }
                append("Known item ids: $knownIds")
            }
        }
    }
}
