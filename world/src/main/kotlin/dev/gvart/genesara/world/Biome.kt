package dev.gvart.genesara.world

enum class Biome {
    FOREST,
    PLAINS,
    MOUNTAIN,
    COASTAL,
    SWAMP,
    RUINS,
    DESERT,
    TUNDRA,

    /**
     * Open sea regions. Unlike land biomes, ocean regions are visually uniform — every hex
     * paints as [Terrain.OCEAN] and the weighted-terrain picker is bypassed entirely
     * (see [paintsUniformly]). Boats unlock traversal in Phase 3; until then the OCEAN
     * terrain is non-traversable, so agents cannot step into it on foot.
     */
    OCEAN;

    /**
     * The hex-tile [Terrain] used to represent this biome when biasing the outer ring of a
     * lazy-seeded hex grid toward the containing region's overall character. For
     * uniformly-painted biomes (see [paintsUniformly]) every hex in the region is set to
     * this terrain, not just the outer ring.
     */
    fun representativeTerrain(): Terrain = when (this) {
        FOREST -> Terrain.FOREST
        PLAINS -> Terrain.PLAINS
        MOUNTAIN -> Terrain.MOUNTAIN
        COASTAL -> Terrain.COASTAL
        SWAMP -> Terrain.SWAMP
        RUINS -> Terrain.ANCIENT_RUINS
        DESERT -> Terrain.DESERT
        TUNDRA -> Terrain.ICE_TUNDRA
        OCEAN -> Terrain.OCEAN
    }

    /**
     * True if every hex inside a region of this biome should be painted with the
     * representative terrain (no weighted-picker variation). Used for biomes whose
     * sub-tile texture is not meaningful — currently only [OCEAN].
     */
    fun paintsUniformly(): Boolean = this == OCEAN
}
