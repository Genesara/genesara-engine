package dev.gvart.genesara.world

data class Region(
    val id: RegionId,
    val worldId: WorldId,
    val sphereIndex: Int,
    val biome: Biome?,
    val climate: Climate?,
    val centroid: Vec3,
    val faceVertices: List<Vec3>,
    val neighbors: Set<RegionId>,
)
