package dev.gvart.genesara.api.internal.mcp.tools.getmap

import dev.gvart.genesara.world.Biome
import dev.gvart.genesara.world.Terrain

data class GetMapResponse(
    val nodes: List<RecalledNodeView>,
)

data class RecalledNodeView(
    val nodeId: Long,
    val regionId: Long,
    val q: Int,
    val r: Int,
    /** Terrain captured at the most recent sighting. May be stale. */
    val terrain: Terrain,
    /** Biome of the containing region — projected at recall time, not snapshotted. */
    val biome: Biome?,
    val firstSeenTick: Long,
    val lastSeenTick: Long,
)
