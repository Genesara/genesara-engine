package dev.gvart.genesara.api.internal.mcp.tools.lookaround

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityTracker
import dev.gvart.genesara.api.internal.mcp.presence.touchActivity
import dev.gvart.genesara.api.internal.mcp.projection.vitalBand
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.world.AgentMapMemoryGateway
import dev.gvart.genesara.world.Building
import dev.gvart.genesara.world.BuildingsLookup
import dev.gvart.genesara.world.DroppedItemView
import dev.gvart.genesara.world.Node
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.NodeMemoryUpdate
import dev.gvart.genesara.world.NodeResources
import dev.gvart.genesara.world.Region
import dev.gvart.genesara.world.VisionRadius
import dev.gvart.genesara.world.WorldQueryGateway
import dev.gvart.genesara.world.GroundItemView as DomainGroundItemView
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.annotation.Tool
import org.springframework.stereotype.Component

@Component
internal class LookAroundTool(
    private val world: WorldQueryGateway,
    private val agents: AgentRegistry,
    private val vision: VisionRadius,
    private val activity: AgentActivityTracker,
    private val tick: TickClock,
    private val mapMemory: AgentMapMemoryGateway,
    private val buildings: BuildingsLookup,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Tool(
        name = "look_around",
        description = "Return the agent's current node and visible adjacent nodes within sight range. " +
            "The current node carries full resource counts and full per-building summaries; adjacent " +
            "nodes carry only item ids and a fog-of-war building summary (type + status, no instance ids).",
    )
    fun invoke(req: LookAroundRequest?, toolContext: ToolContext): LookAroundResponse {
        touchActivity(toolContext, activity, "look_around")
        val agentId = AgentContextHolder.current()
        val agent = agents.find(agentId) ?: error("Agent not registered: $agentId")

        val nodeId = world.locationOf(agentId)
            ?: error("Agent has not spawned yet — call `spawn` first")
        val current = world.node(nodeId) ?: error("Current node not found: $nodeId")
        val sight = vision.radiusFor(agent, nodeId)
        val region = world.region(current.regionId)
            ?: error("Current region not found: ${current.regionId}")

        val currentTick = tick.currentTick()
        val currentResources = world.resourcesAt(current.id, currentTick)
        val currentGroundItems = world.groundItemsAt(current.id)
        val adjacent = adjacentVisibleNodes(nodeId, sight, currentTick)

        // Single round-trip for every visible node's buildings — never call `byNode` in a loop.
        val visibleNodeIds = (adjacent.map { it.first.id } + current.id).toSet()
        val buildingsByNode = buildings.byNodes(visibleNodeIds)

        journalVisibleNodes(agentId, current, region, adjacent, currentTick)

        return LookAroundResponse(
            currentNode = current.toView(region, currentResources, buildingsByNode[current.id].orEmpty(), fogOfWar = false),
            currentResources = currentResources.entries.values.map {
                ResourceView(
                    itemId = it.itemId.value,
                    quantity = it.quantity,
                    initialQuantity = it.initialQuantity,
                )
            },
            groundItems = currentGroundItems.map { it.toView() },
            adjacent = adjacent.map { (n, r, res) ->
                n.toView(r, res, buildingsByNode[n.id].orEmpty(), fogOfWar = true)
            },
        )
    }

    private fun adjacentVisibleNodes(
        nodeId: NodeId,
        sight: Int,
        currentTick: Long,
    ): List<Triple<Node, Region, NodeResources>> =
        world.nodesWithin(nodeId, sight)
            .asSequence()
            .filter { it != nodeId }
            .mapNotNull { id ->
                val n = world.node(id) ?: return@mapNotNull null
                val r = world.region(n.regionId) ?: return@mapNotNull null
                Triple(n, r, world.resourcesAt(n.id, currentTick))
            }
            .toList()

    /**
     * Best-effort fog-of-war journal: every node in current sight (own tile included)
     * becomes a known entry retrievable via `get_map`. Wrapped in try/catch because
     * `look_around` is a read tool — a journaling failure (DB hiccup, lock timeout) must
     * not poison the caller's view; the next call repairs the gap.
     */
    private fun journalVisibleNodes(
        agentId: AgentId,
        current: Node,
        region: Region,
        adjacent: List<Triple<Node, Region, NodeResources>>,
        currentTick: Long,
    ) {
        val seen = buildList {
            add(NodeMemoryUpdate(nodeId = current.id, terrain = current.terrain, biome = region.biome))
            adjacent.forEach { (n, r, _) ->
                add(NodeMemoryUpdate(nodeId = n.id, terrain = n.terrain, biome = r.biome))
            }
        }
        try {
            mapMemory.recordVisible(agentId, seen, currentTick)
        } catch (e: Exception) {
            log.warn("look_around: failed to journal map memory for agent {} at tick {}", agentId, currentTick, e)
        }
    }
}

private fun Node.toView(
    region: Region,
    resources: NodeResources,
    buildings: List<Building>,
    fogOfWar: Boolean,
) = NodeView(
    id = id.value,
    q = q,
    r = r,
    biome = region.biome?.name,
    climate = region.climate?.name,
    terrain = terrain.name,
    pvpEnabled = pvpEnabled,
    resources = resources.entries.keys.map { it.value }.sorted(),
    buildings = buildings
        .sortedBy { it.instanceId }
        .map { it.toSummary(fogOfWar) },
)

private fun DomainGroundItemView.toView(): GroundItemView = when (val payload = drop) {
    is DroppedItemView.Stackable -> GroundItemView(
        dropId = payload.dropId.toString(),
        itemId = payload.item.value,
        droppedAtTick = droppedAtTick,
        kind = "STACKABLE",
        quantity = payload.quantity,
    )
    is DroppedItemView.Equipment -> GroundItemView(
        dropId = payload.dropId.toString(),
        itemId = payload.item.value,
        droppedAtTick = droppedAtTick,
        kind = "EQUIPMENT",
        rarity = payload.rarity.name,
        durabilityCurrent = payload.durabilityCurrent,
        durabilityMax = payload.durabilityMax,
        creatorAgentId = payload.creatorAgentId?.toString(),
        createdAtTick = payload.createdAtTick,
    )
}

private fun Building.toSummary(fogOfWar: Boolean): BuildingSummaryView =
    if (fogOfWar) {
        BuildingSummaryView(type = type.name, status = status.name)
    } else {
        BuildingSummaryView(
            type = type.name,
            status = status.name,
            instanceId = instanceId.toString(),
            progressSteps = progressSteps,
            totalSteps = totalSteps,
            hpBand = vitalBand(hpCurrent, hpMax, zeroLabel = "destroyed"),
            builderAgentId = builtByAgentId.id.toString(),
        )
    }
