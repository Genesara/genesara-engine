package dev.gvart.genesara.world

data class Node(
    val id: NodeId,
    val regionId: RegionId,
    val q: Int,
    val r: Int,
    val terrain: Terrain,
    val adjacency: Set<NodeId>,
)
