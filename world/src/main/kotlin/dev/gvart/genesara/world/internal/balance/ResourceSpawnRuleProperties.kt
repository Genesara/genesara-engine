package dev.gvart.genesara.world.internal.balance

/**
 * YAML binding for one entry under a terrain's `resource-spawns:` list. The Spring
 * Boot binder produces these; [BalanceLookup] converts to the domain
 * [dev.gvart.genesara.world.ResourceSpawnRule].
 *
 * `quantityRange` is a `[min, max]` pair encoded as a list because YAML doesn't have a
 * native range type. The validator at startup ensures size == 2 and `min <= max`.
 */
internal data class ResourceSpawnRuleProperties(
    val item: String = "",
    val spawnChance: Double = 1.0,
    val quantityRange: List<Int> = emptyList(),
)
