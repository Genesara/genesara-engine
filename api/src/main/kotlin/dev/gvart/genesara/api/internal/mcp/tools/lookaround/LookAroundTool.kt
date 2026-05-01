package dev.gvart.genesara.api.internal.mcp.tools.lookaround

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityRegistry
import dev.gvart.genesara.api.internal.mcp.presence.touchActivity
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.player.ClassPropertiesLookup
import dev.gvart.genesara.world.AgentMapMemoryGateway
import dev.gvart.genesara.world.Node
import dev.gvart.genesara.world.NodeMemoryUpdate
import dev.gvart.genesara.world.NodeResources
import dev.gvart.genesara.world.Region
import dev.gvart.genesara.world.WorldQueryGateway
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.annotation.Tool
import org.springframework.stereotype.Component

@Component
internal class LookAroundTool(
    private val world: WorldQueryGateway,
    private val agents: AgentRegistry,
    private val classes: ClassPropertiesLookup,
    private val activity: AgentActivityRegistry,
    private val tick: TickClock,
    private val mapMemory: AgentMapMemoryGateway,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Tool(
        name = "look_around",
        description = "Return the agent's current node and visible adjacent nodes within sight range. The current node carries full resource counts; adjacent nodes carry only item ids (fog-of-war).",
    )
    fun invoke(req: LookAroundRequest, toolContext: ToolContext): LookAroundResponse {
        touchActivity(toolContext, activity)
        val agentId = AgentContextHolder.current()
        val agent = agents.find(agentId) ?: error("Agent not registered: $agentId")
        val sight = classes.sightRange(agent.classId)

        val nodeId = world.locationOf(agentId)
            ?: error("Agent has not spawned yet — call `spawn` first")
        val current = world.node(nodeId) ?: error("Current node not found: $nodeId")
        val region = world.region(current.regionId)
            ?: error("Current region not found: ${current.regionId}")

        val currentTick = tick.currentTick()
        val currentResources = world.resourcesAt(current.id, currentTick)
        val adjacent = adjacentVisibleNodes(nodeId, sight, currentTick)

        journalVisibleNodes(agentId, current, region, adjacent, currentTick)

        return LookAroundResponse(
            currentNode = current.toView(region, currentResources),
            currentResources = currentResources.entries.values.map {
                ResourceView(
                    itemId = it.itemId.value,
                    quantity = it.quantity,
                    initialQuantity = it.initialQuantity,
                )
            },
            adjacent = adjacent.map { (n, r, res) -> n.toView(r, res) },
        )
    }

    private fun adjacentVisibleNodes(
        nodeId: dev.gvart.genesara.world.NodeId,
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

private fun Node.toView(region: Region, resources: NodeResources) = NodeView(
    id = id.value,
    q = q,
    r = r,
    biome = region.biome?.name,
    climate = region.climate?.name,
    terrain = terrain.name,
    pvpEnabled = pvpEnabled,
    resources = resources.entries.keys.map { it.value }.sorted(),
)
