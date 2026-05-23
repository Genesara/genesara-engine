package dev.gvart.genesara.api.internal.mcp.tools.transport

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityTracker
import dev.gvart.genesara.api.internal.mcp.presence.touchActivity
import dev.gvart.genesara.api.internal.mcp.tools.PrefixedIds
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.world.WorldCommandGateway
import dev.gvart.genesara.world.commands.EnvironmentCommand
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component

@Component
internal class ReleaseTransportTool(
    private val world: WorldCommandGateway,
    private val engine: TickClock,
    private val activity: AgentActivityTracker,
) {

    @Tool(
        name = "release_transport",
        description = "Relinquish ownership of one of your mounts. The transport id must be wire-prefixed as " +
            "`mount:<uuid>` exactly as returned by `look_around` / `inspect_mount`. Clears `owner_agent_id` so " +
            "any same-node agent under their mount cap can `claim_transport` it. Rejected if the mount isn't " +
            "yours or you're not at the same node.",
    )
    fun invoke(
        @ToolParam(required = true, description = "Wire-prefixed transport id — must be `mount:<uuid>`.")
        transport_id: String,
        toolContext: ToolContext,
    ): TransportTransferResponse {
        touchActivity(toolContext, activity, "release_transport")
        val parsed = PrefixedIds.parseMount(transport_id)
            ?: return TransportTransferResponse.rejected(
                target = transport_id,
                reason = "bad_target_id",
                detail = "transport_id must be mount:<uuid>",
            )
        val agent = AgentContextHolder.current()
        val command = EnvironmentCommand.ReleaseTransport(agent = agent, mount = parsed)
        val appliesAtTick = world.submit(command, appliesAtTick = engine.currentTick() + 1)
        return TransportTransferResponse.queued(command.commandId, appliesAtTick, PrefixedIds.encodeMount(parsed))
    }
}
