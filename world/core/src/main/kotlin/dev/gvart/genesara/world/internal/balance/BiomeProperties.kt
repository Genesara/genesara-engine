package dev.gvart.genesara.world.internal.balance

data class BiomeProperties(
    val displayName: String,
    val staminaCostMultiplier: Double = 0.0,
    /**
     * Cap on how many Tier-A NPCs the lazy-on-entry seeder will place in a
     * single node of this biome. 0 disables fauna spawning in this biome.
     */
    val nodeNpcCapacity: Int = 0,
    /** Per-node probability `[0.0, 1.0]` that the lazy seeder admits this biome's nodes. */
    val nodeSpawnProbability: Double = 0.10,
)
