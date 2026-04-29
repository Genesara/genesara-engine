package dev.gvart.genesara.api.internal.mcp.tools.lookaround

import com.fasterxml.jackson.annotation.JsonClassDescription

@JsonClassDescription("Return the agent's current node and the visible adjacent nodes within its sight range. The current node also includes per-resource quantities; adjacent nodes show only resource ids (no counts) as a fog-of-war approximation.")
class LookAroundRequest

data class LookAroundResponse(
    val currentNode: NodeView,
    val currentResources: List<ResourceView>,
    val adjacent: List<NodeView>,
)

data class NodeView(
    val id: Long,
    val q: Int,
    val r: Int,
    val biome: String?,
    val climate: String?,
    val terrain: String,
    /** Item ids visible at this node. For the current node these accompany [LookAroundResponse.currentResources] which carries the quantities. */
    val resources: List<String>,
)

data class ResourceView(
    val itemId: String,
    val quantity: Int,
    val initialQuantity: Int,
)
