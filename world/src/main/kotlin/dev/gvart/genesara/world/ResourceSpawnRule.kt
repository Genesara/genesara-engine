package dev.gvart.genesara.world

/**
 * A single resource-spawn rule on a terrain. At world-paint time the spawner rolls
 * one rule per item per node: with probability [spawnChance] the node is seeded with
 * a quantity drawn uniformly from [quantityRange]. Multiple rules per terrain are
 * independent rolls — a forest node might have WOOD and BERRY but not HERB.
 *
 * `spawnChance` outside [0.0, 1.0] and inverted/empty [quantityRange]s are caught at
 * startup by the spawn-rules validator.
 */
data class ResourceSpawnRule(
    val item: ItemId,
    val spawnChance: Double,
    val quantityRange: IntRange,
)
