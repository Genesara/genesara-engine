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
    /**
     * True if PvP is allowed on this tile. Defaults to true everywhere outside Phase 2/3
     * green zones (capital cities, clan homes). Surfaced now so agents can pick it up
     * once those zones land without needing a payload-shape change.
     */
    val pvpEnabled: Boolean,
    /** Item ids visible at this node. For the current node these accompany [LookAroundResponse.currentResources] which carries the quantities. */
    val resources: List<String>,
    /**
     * Buildings visible at this node. On the current tile every instance is enumerated with
     * its full per-instance summary (id, type, status, progress, owner). Adjacent tiles carry
     * only type + status + count (fog-of-war analogous to resources).
     */
    val buildings: List<BuildingSummaryView> = emptyList(),
)

data class ResourceView(
    val itemId: String,
    val quantity: Int,
    val initialQuantity: Int,
)

/**
 * Per-building summary returned by `look_around`. On the agent's current node every field is
 * populated with the live instance state. Adjacent-node entries use the same shape but
 * intentionally omit `instanceId`, `progressSteps`, `totalSteps`, `hpBand`, and `builderAgentId`
 * — fog-of-war keeps remote tiles to type + status + a node-local count.
 */
data class BuildingSummaryView(
    val type: String,
    val status: String,
    val instanceId: String? = null,
    val progressSteps: Int? = null,
    val totalSteps: Int? = null,
    val hpBand: String? = null,
    val builderAgentId: String? = null,
)
