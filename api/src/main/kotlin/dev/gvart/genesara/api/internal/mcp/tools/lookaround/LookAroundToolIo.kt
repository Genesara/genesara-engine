package dev.gvart.genesara.api.internal.mcp.tools.lookaround

import dev.gvart.genesara.world.Rarity

data class LookAroundResponse(
    val currentNode: NodeView,
    val currentResources: List<ResourceView>,
    /**
     * Drops sitting on the agent's current tile, ready to be picked up via the
     * `pickup` MCP tool. Empty when nothing has been dropped here. Non-current
     * nodes intentionally do not surface ground items (fog-of-war parity with
     * resources).
     */
    val groundItems: List<GroundItemView> = emptyList(),
    /** Every node within the agent's sight radius (current node excluded). Fog-of-war. */
    val visible: List<NodeView>,
    /** Ids of the hex-adjacent neighbours — the legal one-step `move` targets from the current node. */
    val neighbours: List<Long>,
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
    /**
     * Other active agents present at this node. Populated only on the current tile —
     * adjacent tiles never carry agent presence (separate fog-of-war problem). The
     * calling agent is excluded; self info is already on `get_status`.
     */
    val agents: List<AgentPresenceView> = emptyList(),
)

/**
 * Discovery row for another agent in the same node. Carries enough to address the agent
 * (`id` for `attack(targetAgentId=…)`) and assess them at a coarse band — `hpBand` is the
 * shared low/mid/high projection from `vitalBand`; the raw HP is never exposed.
 */
data class AgentPresenceView(
    val id: String,
    val name: String,
    val race: String,
    val level: Int,
    val hpBand: String,
)

data class ResourceView(
    val itemId: String,
    val quantity: Int,
    val initialQuantity: Int,
)

/**
 * One ground item visible at the agent's current node. [dropId] is the handle
 * the agent passes to the `pickup` MCP tool. [kind] discriminates between
 * stackable and equipment payloads — stackable rows populate [quantity];
 * equipment rows populate [rarity], [durabilityCurrent], [durabilityMax],
 * [creatorAgentId], and [createdAtTick].
 */
data class GroundItemView(
    val dropId: String,
    val itemId: String,
    val droppedAtTick: Long,
    val kind: GroundItemKind,
    val quantity: Int? = null,
    val rarity: Rarity? = null,
    val durabilityCurrent: Int? = null,
    val durabilityMax: Int? = null,
    val creatorAgentId: String? = null,
    val createdAtTick: Long? = null,
)

enum class GroundItemKind { STACKABLE, EQUIPMENT }

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
    /** FARM_PLOT plot id on current tile; null on adjacent (fog-of-war). Pass to `plant`/`tend`. */
    val plotId: String? = null,
    /** Planted crop id; surfaces on adjacent tiles too so observers can see something is growing. */
    val plantedCrop: String? = null,
    /** Ticks remaining before the planted crop becomes harvestable. 0 when ripe. */
    val ticksToRipe: Long? = null,
    /** Ticks remaining before the neglect sweep would clear the plot. 0 when neglected. */
    val ticksUntilNeglect: Long? = null,
)
