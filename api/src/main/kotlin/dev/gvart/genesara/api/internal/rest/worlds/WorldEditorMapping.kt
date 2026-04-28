package dev.gvart.genesara.api.internal.rest.worlds

import dev.gvart.genesara.world.Node
import dev.gvart.genesara.world.Region
import dev.gvart.genesara.world.World
import java.time.format.DateTimeFormatter

internal fun World.toDto(): WorldDto = WorldDto(
    id = id.value,
    name = name,
    nodeCount = nodeCount,
    nodeSize = nodeSize,
    createdAt = DateTimeFormatter.ISO_INSTANT.format(createdAt),
)

internal fun Region.toDto(neighborIndicesBySphere: Map<Long, Int>): GlobeNodeDto = GlobeNodeDto(
    id = id.value,
    worldId = worldId.value,
    sphereIndex = sphereIndex,
    biome = biome?.name,
    climate = climate?.name,
    faceVertices = faceVertices.map { listOf(it.x, it.y, it.z) },
    centroid = listOf(centroid.x, centroid.y, centroid.z),
    neighborIndices = neighbors.mapNotNull { neighborIndicesBySphere[it.value] }.sorted(),
)

internal fun Node.toDto(): HexNodeDto = HexNodeDto(
    id = id.value,
    q = q,
    r = r,
    terrain = terrain.name,
)
