package dev.gvart.genesara.world

/**
 * Catalog row for one NPC type. Loaded from `world-definition/npcs.yaml`.
 * Combat-side tuning sits on the type, not the per-instance row, so a balance
 * change retunes every live spawn on next tick without rewriting Postgres rows.
 */
data class NpcDef(
    val type: NpcType,
    val displayName: String,
    val hpMax: Int,
    val damage: Int,
    val damageType: DamageType,
    /** Hops along node adjacency the NPC can strike across. 1 = same-node melee. */
    val range: Int,
    /** Minimum ticks between successive attacks from a single NPC. */
    val attackIntervalTicks: Int,
    /** Flat mitigation subtracted from incoming raw damage (clamped at zero). */
    val defense: Int,
    /** Percent chance the NPC dodges an incoming swing. 0 disables. */
    val dodgeChancePercent: Int,
    val aggressionProfile: AggressionProfile,
    /**
     * For TERRITORIAL: hops from `spawnNodeId` within which the NPC behaves
     * HOSTILE. 0 means spawn-node-only. Ignored for non-TERRITORIAL.
     */
    val territoryRadius: Int = 0,
    /** Biomes this NPC can spawn in via the lazy-on-entry seeder. */
    val spawnBiomes: Set<Biome> = emptySet(),
    /** Weight for the per-biome capacity-fill weighted random pick. */
    val spawnWeight: Int = 1,
)
