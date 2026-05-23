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
internal class ClaimTransportTool(
    private val world: WorldCommandGateway,
    private val engine: TickClock,
    private val activity: AgentActivityTracker,
) {

    @Tool(
        name = "claim_transport",
        description = "Take ownership of an ownerless mount that is at your node. The transport id must be " +
            "wire-prefixed as `mount:<uuid>` exactly as returned by `look_around` / `inspect_mount`. Rejected " +
            "if the mount already has an owner, you're not at the same node, or you've hit your mount cap " +
            "(cap = 1 + ANIMAL_HANDLING/50, max 4).",
    )
    fun invoke(
        @ToolParam(required = true, description = "Wire-prefixed transport id — must be `mount:<uuid>`.")
        transport_id: String,
        toolContext: ToolContext,
    ): TransportTransferResponse {
        touchActivity(toolContext, activity, "claim_transport")
        val parsed = PrefixedIds.parseMount(transport_id)
            ?: return TransportTransferResponse.rejected(
                target = transport_id,
                reason = "bad_target_id",
                detail = "transport_id must be mount:<uuid>",
            )
        val agent = AgentContextHolder.current()
        val command = EnvironmentCommand.ClaimTransport(agent = agent, mount = parsed)
        val appliesAtTick = world.submit(command, appliesAtTick = engine.currentTick() + 1)
        return TransportTransferResponse.queued(command.commandId, appliesAtTick, PrefixedIds.encodeMount(parsed))
    }
}
