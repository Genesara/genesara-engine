package dev.gvart.genesara.api.internal.mcp.tools.transport

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityTracker
import dev.gvart.genesara.api.internal.mcp.presence.touchActivity
import dev.gvart.genesara.api.internal.mcp.tools.CommandAckResponse
import dev.gvart.genesara.api.internal.mcp.tools.PrefixedIds
import dev.gvart.genesara.api.internal.mcp.tools.submitQueued
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.world.WorldCommandGateway
import dev.gvart.genesara.world.commands.EnvironmentCommand
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component

@Component
internal class MountTransportTool(
    private val world: WorldCommandGateway,
    private val engine: TickClock,
    private val activity: AgentActivityTracker,
) {

    @Tool(
        name = "mount",
        description = "Climb onto a transport (mount today, vehicles later) you're same-node with. " +
            "The id is wire-prefixed as `mount:<uuid>` (from `look_around` / `inspect`). Mounts have " +
            "no per-agent ownership; any agent can ride any idle mount. Rejects if the mount is being " +
            "ridden, dead, not same-node, or you're already on another mount. While mounted, movement " +
            "uses mount fatigue and follows speed-factor; ground-work verbs (harvest/extract/craft/" +
            "build/pickup/tame) require dismount.",
    )
    fun invoke(
        @ToolParam(required = true, description = "Wire-prefixed id `mount:<uuid>`.")
        transport_id: String,
        toolContext: ToolContext,
    ): CommandAckResponse {
        touchActivity(toolContext, activity, "mount")
        val parsed = PrefixedIds.parseMount(transport_id)
            ?: return CommandAckResponse.rejected(transport_id, "bad_target_id", "id must be mount:<uuid>")
        val agent = AgentContextHolder.current()
        val command = EnvironmentCommand.MountTransport(agent = agent, mount = parsed)
        return world.submitQueued(command, engine, target = PrefixedIds.encodeMount(parsed))
    }
}

@Component
internal class DismountTransportTool(
    private val world: WorldCommandGateway,
    private val engine: TickClock,
    private val activity: AgentActivityTracker,
) {

    @Tool(
        name = "dismount",
        description = "Step off whatever mount you're currently riding. Rejected with `not_mounted` if " +
            "you're already on foot. Required before ground-work verbs (harvest/extract/craft/build/" +
            "pickup/tame). The mount remains at its current node, idle (fatigue regenerates while " +
            "unridden).",
    )
    fun invoke(toolContext: ToolContext): CommandAckResponse {
        touchActivity(toolContext, activity, "dismount")
        val agent = AgentContextHolder.current()
        val command = EnvironmentCommand.DismountTransport(agent = agent)
        return world.submitQueued(command, engine)
    }
}
