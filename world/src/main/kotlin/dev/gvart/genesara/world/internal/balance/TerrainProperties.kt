package dev.gvart.genesara.world.internal.balance

internal data class TerrainProperties(
    val displayName: String,
    val traversable: Boolean = true,
    val movementCostMultiplier: Double = .5,
    /**
     * Per-item spawn rules for this terrain. Each rule rolls independently at
     * world-paint time: with probability `spawnChance` the node receives a quantity
     * drawn uniformly from `quantityRange`. The set of rules defines what a terrain
     * *can* produce; the live availability per node is in the resource store.
     */
    val resourceSpawns: List<ResourceSpawnRuleProperties> = emptyList(),
    /**
     * Whether the `drink` verb is available on this terrain. Tagged on terrains with
     * meaningful surface water (coastal, river delta, wetlands, shoreline). Inventory-
     * carried water items work anywhere — this flag only governs the in-the-wild path.
     */
    val waterSource: Boolean = false,
)