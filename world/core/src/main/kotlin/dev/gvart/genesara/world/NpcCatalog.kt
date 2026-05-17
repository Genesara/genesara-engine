package dev.gvart.genesara.world

interface NpcCatalog {
    fun byType(type: NpcType): NpcDef?
    fun all(): Collection<NpcDef>
    /** NPC defs eligible to spawn in [biome]. */
    fun byBiome(biome: Biome): List<NpcDef>
}
