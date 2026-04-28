package dev.gvart.genesara.world.internal.balance

import dev.gvart.genesara.world.ItemLookup
import jakarta.annotation.PostConstruct
import org.springframework.stereotype.Component

/**
 * Cross-validates `terrains.yaml` against `items.yaml` at startup. Each terrain's
 * `gatherables` list must reference item ids that exist in the item catalog — otherwise
 * the world author has shipped an unreachable resource (silent foot-gun: agents see
 * `ResourceNotAvailableHere` on every spelling they try, and there's nothing to grep for
 * the typo).
 *
 * Fails fast on misconfiguration. Keep validation here (not in the lookup) so the
 * runtime hot path stays branch-free.
 */
@Component
internal class GatherablesValidator(
    private val world: WorldDefinitionProperties,
    private val items: ItemLookup,
) {

    @PostConstruct
    fun validate() {
        val knownIds = items.all().map { it.id.value }.toSet()
        val problems = world.terrains.mapNotNull { (terrain, terrainProps) ->
            val unknown = terrainProps.gatherables.toSet() - knownIds
            if (unknown.isEmpty()) null else terrain to unknown
        }
        require(problems.isEmpty()) {
            buildString {
                append("Terrain catalog references unknown item ids:\n")
                problems.forEach { (terrain, unknown) ->
                    append("  $terrain → $unknown\n")
                }
                append("Known item ids: $knownIds")
            }
        }
    }
}
