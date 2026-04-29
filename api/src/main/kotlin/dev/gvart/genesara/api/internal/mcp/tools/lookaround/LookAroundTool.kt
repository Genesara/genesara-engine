package dev.gvart.genesara.api.internal.mcp.tools.lookaround

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityRegistry
import dev.gvart.genesara.api.internal.mcp.presence.touchActivity
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.player.ClassPropertiesLookup
import dev.gvart.genesara.world.Node
import dev.gvart.genesara.world.NodeResources
import dev.gvart.genesara.world.Region
import dev.gvart.genesara.world.WorldQueryGateway
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
) {

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

        val adjacent = world.nodesWithin(nodeId, sight)
            .asSequence()
            .filter { it != nodeId }
            .mapNotNull { id ->
                val n = world.node(id) ?: return@mapNotNull null
                val r = world.region(n.regionId) ?: return@mapNotNull null
                // Fog-of-war: ids only, no quantities. The store call still resolves
                // (cheap — single index hit on a small table), but only the keys leak out.
                val res = world.resourcesAt(n.id, currentTick)
                Triple(n, r, res)
            }
            .toList()

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
}

private fun Node.toView(region: Region, resources: NodeResources) = NodeView(
    id = id.value,
    q = q,
    r = r,
    biome = region.biome?.name,
    climate = region.climate?.name,
    terrain = terrain.name,
    resources = resources.entries.keys.map { it.value }.sorted(),
)
