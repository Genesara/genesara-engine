package dev.gvart.genesara.world

data class Node(
    val id: NodeId,
    val regionId: RegionId,
    val q: Int,
    val r: Int,
    val terrain: Terrain,
    val adjacency: Set<NodeId>,
    /**
     * Whether PvP combat is allowed on this tile. Defaults to true everywhere — green
     * zones (capital cities, clan homes) flip this to false when the zoning systems
     * land in Phase 2 / Phase 3. Surfaced to agents via `look_around` so an agent
     * stepping into / near a green zone can recognise it without an extra tool call.
     */
    val pvpEnabled: Boolean = true,
)
