package dev.gvart.genesara.api.internal.mcp.tools.spawn

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityTracker
import dev.gvart.genesara.api.internal.mcp.presence.touchActivity
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.player.AgentProfileLookup
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.world.WorldCommandGateway
import dev.gvart.genesara.world.WorldQueryGateway
import dev.gvart.genesara.world.commands.CoreCommand
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.annotation.Tool
import org.springframework.stereotype.Component

@Component
internal class SpawnTool(
    private val world: WorldCommandGateway,
    private val worldQuery: WorldQueryGateway,
    private val engine: TickClock,
    private val activity: AgentActivityTracker,
    private val agents: AgentRegistry,
    private val profiles: AgentProfileLookup,
) {

    @Tool(
        name = "spawn",
        description = "Login: enter the world. The simulation chooses the destination — last node if the agent has played before, otherwise their race's starter node, falling back to a random spawnable node. The resolved node is reported on the resulting agent.spawned event. " +
            "initialLocation and initialHp in the response are synchronous projections of the expected post-spawn state — use them instead of calling get_status immediately after spawn.",
    )
    fun invoke(toolContext: ToolContext): SpawnResponse {
        touchActivity(toolContext, activity, "spawn")
        val agentId = AgentContextHolder.current()
        val command = CoreCommand.SpawnAgent(agent = agentId)
        val appliesAtTick = world.submit(command, appliesAtTick = engine.currentTick() + 1)

        val agent = agents.find(agentId)
        val existingBody = worldQuery.bodyOf(agentId)
        val existingLocation = worldQuery.locationOf(agentId)
            ?: agent?.race?.let { worldQuery.starterNodeFor(it) }
            ?: worldQuery.randomSpawnableNode()
        val initialHp = existingBody?.hp ?: profiles.find(agentId)?.maxHp

        return SpawnResponse(
            commandId = command.commandId,
            appliesAtTick = appliesAtTick,
            initialLocation = existingLocation?.value,
            initialHp = initialHp,
        )
    }
}
