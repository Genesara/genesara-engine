package dev.gvart.agenticrpg.api.internal.mcp.tools.lookaround

import com.fasterxml.jackson.annotation.JsonClassDescription

@JsonClassDescription("Return the agent's current node and the visible adjacent nodes within its sight range.")
class LookAroundRequest

data class LookAroundResponse(
    val currentNode: NodeView,
    val adjacent: List<NodeView>,
)

data class NodeView(
    val id: Long,
    val q: Int,
    val r: Int,
    val biome: String?,
    val climate: String?,
    val terrain: String,
    val resources: List<String>,
)
