package dev.gvart.genesara.api.internal.mcp.tools.lookaround

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityTracker
import dev.gvart.genesara.api.internal.mcp.presence.touchActivity
import dev.gvart.genesara.api.internal.mcp.projection.vitalBand
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.world.AgentMapMemoryGateway
import dev.gvart.genesara.world.AgentPlot
import dev.gvart.genesara.world.AgentPlotsStore
import dev.gvart.genesara.world.Building
import dev.gvart.genesara.world.BuildingType
import dev.gvart.genesara.world.BuildingsLookup
import dev.gvart.genesara.world.CropLookup
import dev.gvart.genesara.world.DroppedItemView
import dev.gvart.genesara.world.Node
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.NodeMemoryUpdate
import dev.gvart.genesara.world.NodeResources
import dev.gvart.genesara.world.Region
import dev.gvart.genesara.world.VisionRadius
import dev.gvart.genesara.world.WorldQueryGateway
import dev.gvart.genesara.world.GroundItemView as DomainGroundItemView
import java.util.UUID
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
    private val mapMemory: AgentMapMemoryGateway,
    private val buildings: BuildingsLookup,
    private val plots: AgentPlotsStore,
    private val crops: CropLookup,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Tool(
        name = "look_around",
        description = "Return the agent's current node, every node within sight range (`visible`), and " +
            "the ids of the hex-adjacent one-step `move` targets (`neighbours`). The current node " +
            "carries full resource counts, full per-building summaries, and `agents` — other agents " +
            "present on the same tile (id/name/race/level/hpBand), suitable for `attack` targeting. " +
            "Visible non-current nodes carry only item ids and a fog-of-war building summary " +
            "(type + status, no instance ids, no agents). `neighbours` is the canonical input for " +
            "`move`; not every entry in `visible` is move-legal.",
    )
    fun invoke(toolContext: ToolContext): LookAroundResponse {
        touchActivity(toolContext, activity, "look_around")
        val agentId = AgentContextHolder.current()
        val agent = agents.find(agentId) ?: error("Agent not registered: $agentId")

        val nodeId = world.locationOf(agentId)
            ?: error("Agent has not spawned yet — call `spawn` first")
        val current = world.node(nodeId) ?: error("Current node not found: $nodeId")
        val sight = vision.radiusFor(agent, nodeId)
        val region = world.region(current.regionId)
            ?: error("Current region not found: ${current.regionId}")

        val currentTick = world.currentTickFor(agentId)
        val currentResources = world.resourcesAt(current.id, currentTick)
        val currentGroundItems = world.groundItemsAt(current.id)
        val visible = adjacentVisibleNodes(nodeId, sight, currentTick)

        // Single round-trip for every visible node's buildings — never call `byNode` in a loop.
        val visibleNodeIds = (visible.map { it.first.id } + current.id).toSet()
        val buildingsByNode = buildings.byNodes(visibleNodeIds)
        val plotsByBuilding: Map<UUID, AgentPlot> = plots
            .listByNodes(visibleNodeIds)
            .values
            .flatten()
            .associateBy { it.buildingInstanceId }

        val currentNodeAgents = projectAgentsAt(current.id, excluding = agentId)

        journalVisibleNodes(agentId, current, region, visible, currentTick)

        return LookAroundResponse(
            currentNode = current.toView(
                region,
                currentResources,
                buildingsByNode[current.id].orEmpty(),
                currentNodeAgents,
                fogOfWar = false,
                plotsByBuilding = plotsByBuilding,
                cropLookup = crops,
                currentTick = currentTick,
            ),
            currentResources = currentResources.entries.values.map {
                ResourceView(
                    itemId = it.itemId.value,
                    quantity = it.quantity,
                    initialQuantity = it.initialQuantity,
                )
            },
            groundItems = currentGroundItems.map { it.toView() },
            visible = visible.map { (n, r, res) ->
                n.toView(
                    r, res, buildingsByNode[n.id].orEmpty(), emptyList(), fogOfWar = true,
                    plotsByBuilding = plotsByBuilding, cropLookup = crops, currentTick = currentTick,
                )
            },
            neighbours = current.adjacency.map { it.value }.sorted(),
        )
    }

    private fun projectAgentsAt(nodeId: NodeId, excluding: AgentId): List<AgentPresenceView> {
        val occupants = world.activeAgentsAtNodes(setOf(nodeId))[nodeId].orEmpty()
        return occupants
            .asSequence()
            .filter { it != excluding }
            .sortedBy { it.id }
            .mapNotNull { otherId ->
                val other = agents.find(otherId) ?: return@mapNotNull null
                val body = world.bodyOf(otherId) ?: return@mapNotNull null
                AgentPresenceView(
                    id = other.id.id.toString(),
                    name = other.name,
                    race = other.race.value,
                    level = other.level,
                    hpBand = vitalBand(body.hp, body.maxHp, zeroLabel = "dead"),
                )
            }
            .toList()
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
    agents: List<AgentPresenceView>,
    fogOfWar: Boolean,
    plotsByBuilding: Map<UUID, AgentPlot>,
    cropLookup: CropLookup,
    currentTick: Long,
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
        .map { it.toSummary(fogOfWar, plotsByBuilding, cropLookup, currentTick) },
    agents = agents,
)

private fun DomainGroundItemView.toView(): GroundItemView = when (val payload = drop) {
    is DroppedItemView.Stackable -> GroundItemView(
        dropId = payload.dropId.toString(),
        itemId = payload.item.value,
        droppedAtTick = droppedAtTick,
        kind = GroundItemKind.STACKABLE,
        quantity = payload.quantity,
    )
    is DroppedItemView.Equipment -> GroundItemView(
        dropId = payload.dropId.toString(),
        itemId = payload.item.value,
        droppedAtTick = droppedAtTick,
        kind = GroundItemKind.EQUIPMENT,
        rarity = payload.rarity,
        durabilityCurrent = payload.durabilityCurrent,
        durabilityMax = payload.durabilityMax,
        creatorAgentId = payload.creatorAgentId?.toString(),
        createdAtTick = payload.createdAtTick,
    )
}

private fun Building.toSummary(
    fogOfWar: Boolean,
    plotsByBuilding: Map<UUID, AgentPlot>,
    crops: CropLookup,
    currentTick: Long,
): BuildingSummaryView {
    val plot = if (type == BuildingType.FARM_PLOT) plotsByBuilding[instanceId] else null
    val plant = plot?.plant
    val crop = plant?.let { crops.byId(it.cropId) }

    return if (fogOfWar) {
        // Adjacent tiles see the planted-crop name but no timing details.
        BuildingSummaryView(
            type = type.name,
            status = status.name,
            plantedCrop = plant?.cropId?.value,
        )
    } else {
        BuildingSummaryView(
            type = type.name,
            status = status.name,
            instanceId = instanceId.toString(),
            progressSteps = progressSteps,
            totalSteps = totalSteps,
            hpBand = vitalBand(hpCurrent, hpMax, zeroLabel = "destroyed"),
            builderAgentId = builtByAgentId.id.toString(),
            plotId = plot?.plotId?.toString(),
            plantedCrop = plant?.cropId?.value,
            ticksToRipe = if (plant != null && crop != null) {
                ((plant.plantedAtTick + crop.ticksToRipe) - currentTick).coerceAtLeast(0L)
            } else null,
            ticksUntilNeglect = if (plant != null && crop != null) {
                ((plant.lastTendedAtTick + crop.neglectWindowTicks) - currentTick).coerceAtLeast(0L)
            } else null,
        )
    }
}
