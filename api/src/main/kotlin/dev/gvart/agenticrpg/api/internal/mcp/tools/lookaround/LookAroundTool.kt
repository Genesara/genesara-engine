package dev.gvart.agenticrpg.api.internal.mcp.tools.lookaround

import dev.gvart.agenticrpg.api.internal.mcp.presence.AgentActivityRegistry
import dev.gvart.agenticrpg.api.internal.mcp.context.AgentContextHolder
import dev.gvart.agenticrpg.api.internal.mcp.presence.touchActivity
import dev.gvart.agenticrpg.player.AgentRegistry
import dev.gvart.agenticrpg.player.ClassPropertiesLookup
import dev.gvart.agenticrpg.world.Node
import dev.gvart.agenticrpg.world.Region
import dev.gvart.agenticrpg.world.WorldQueryGateway
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.annotation.Tool
import org.springframework.stereotype.Component

@Component
internal class LookAroundTool(
    private val world: WorldQueryGateway,
    private val agents: AgentRegistry,
    private val classes: ClassPropertiesLookup,
    private val activity: AgentActivityRegistry,
) {

    @Tool(
        name = "look_around",
        description = "Return the agent's current node and visible adjacent nodes within sight range.",
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

        val visible = world.nodesWithin(nodeId, sight)
            .asSequence()
            .filter { it != nodeId }
            .mapNotNull { id ->
                val n = world.node(id) ?: return@mapNotNull null
                val r = world.region(n.regionId) ?: return@mapNotNull null
                n to r
            }
            .toList()

        return LookAroundResponse(
            currentNode = current.toView(region),
            adjacent = visible.map { (n, r) -> n.toView(r) },
        )
    }
}

private fun Node.toView(region: Region) = NodeView(
    id = id.value,
    q = q,
    r = r,
    biome = region.biome?.name,
    climate = region.climate?.name,
    terrain = terrain.name,
    resources = emptyList(),
)
