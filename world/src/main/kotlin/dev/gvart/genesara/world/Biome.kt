package dev.gvart.genesara.world

enum class Biome {
    FOREST,
    PLAINS,
    MOUNTAIN,
    COASTAL,
    SWAMP,
    RUINS,
    DESERT,
    TUNDRA;

    /**
     * The hex-tile [Terrain] used to represent this biome when biasing the outer ring of a
     * lazy-seeded hex grid toward the containing region's overall character.
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
    }
}
