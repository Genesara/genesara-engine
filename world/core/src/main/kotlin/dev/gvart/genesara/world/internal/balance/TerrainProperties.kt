package dev.gvart.genesara.world.internal.balance

data class TerrainProperties(
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
    /**
     * Tier on the line-of-sight ladder. 0 = baseline (plains, water, forest, roads);
     * 1 = mid (hills, foothills); 2 = peak (mountain, alpine, cliffside, canyon). Read
     * by [dev.gvart.genesara.world.internal.vision.VisibleNodesImpl] for two purposes:
     * (a) as the observer's base effective height when standing on this terrain, and
     * (b) as the intermediate-tile blocking height during LOS BFS.
     */
    val height: Int = 0,
)