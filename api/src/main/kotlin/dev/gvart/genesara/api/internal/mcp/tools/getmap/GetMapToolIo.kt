package dev.gvart.genesara.api.internal.mcp.tools.getmap

import com.fasterxml.jackson.annotation.JsonClassDescription

@JsonClassDescription(
    "Return every node the agent has had in vision, in the order they were first seen. " +
        "This is fog-of-war recall — entries snapshot the terrain at last sighting and may " +
        "be stale relative to the current world state. Empty for fresh agents who haven't " +
        "called `look_around` yet.",
)
class GetMapRequest

data class GetMapResponse(
    val nodes: List<RecalledNodeView>,
)

data class RecalledNodeView(
    val nodeId: Long,
    val regionId: Long,
    val q: Int,
    val r: Int,
    /** Terrain captured at the most recent sighting. May be stale. */
    val terrain: String,
    /** Biome of the containing region — projected at recall time, not snapshotted. */
    val biome: String?,
    val firstSeenTick: Long,
    val lastSeenTick: Long,
)
