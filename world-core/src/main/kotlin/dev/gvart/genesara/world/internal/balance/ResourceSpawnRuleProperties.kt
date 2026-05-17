package dev.gvart.genesara.world.internal.balance

import dev.gvart.genesara.world.ResourceItemId

/**
 * YAML binding for one entry under a terrain's `resource-spawns:` list. The Spring
 * Boot binder produces these; [BalanceLookup] converts to the domain
 * [dev.gvart.genesara.world.ResourceSpawnRule].
 *
 * `item` is typed against the [ResourceItemId] enum so the binder fails fast on a
 * typo in `terrains.yaml`. The default is null so a partially-built test fixture
 * (no `resource-spawns:` block at all) still binds; the lookup drops null-item
 * entries.
 *
 * `quantityRange` is a `[min, max]` pair encoded as a list because YAML doesn't have a
 * native range type. The validator at startup ensures size == 2 and `min <= max`.
 */
internal data class ResourceSpawnRuleProperties(
    val item: ResourceItemId? = null,
    val spawnChance: Double = 1.0,
    val quantityRange: List<Int> = emptyList(),
)
