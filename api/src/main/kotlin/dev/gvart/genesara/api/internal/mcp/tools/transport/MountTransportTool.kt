package dev.gvart.genesara.api.internal.mcp.tools.transport

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityTracker
import dev.gvart.genesara.api.internal.mcp.presence.touchActivity
import dev.gvart.genesara.api.internal.mcp.tools.CommandAckKind
import dev.gvart.genesara.api.internal.mcp.tools.PrefixedIds
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.world.WorldCommandGateway
import dev.gvart.genesara.world.commands.EnvironmentCommand
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component
import java.util.UUID

@Component
internal class MountTransportTool(
    private val world: WorldCommandGateway,
    private val engine: TickClock,
    private val activity: AgentActivityTracker,
) {

    @Tool(
        name = "mount",
        description = "Climb onto a transport (mount today, vehicles later) you're same-node with. " +
            "The id is wire-prefixed as `mount:<uuid>` (from `look_around` / `inspect_mount`). Open " +
            "riding: any agent can ride any idle mount, owner or not — the owner gets a notification " +
            "event. Rejects if the mount is being ridden, dead, not same-node, or you're already on " +
            "another mount. While mounted, movement uses mount fatigue and follows speed-factor; " +
            "ground-work verbs (harvest/extract/craft/build/pickup/tame) require dismount.",
    )
    fun invoke(
        @ToolParam(required = true, description = "Wire-prefixed id `mount:<uuid>`.")
        transport_id: String,
        toolContext: ToolContext,
    ): TransportAckResponse {
        touchActivity(toolContext, activity, "mount")
        val parsed = PrefixedIds.parseMount(transport_id)
            ?: return TransportAckResponse.rejected(transport_id, "bad_target_id", "id must be mount:<uuid>")
        val agent = AgentContextHolder.current()
        val command = EnvironmentCommand.MountTransport(agent = agent, mount = parsed)
        val appliesAtTick = world.submit(command, appliesAtTick = engine.currentTick() + 1)
        return TransportAckResponse.queued(command.commandId, appliesAtTick, PrefixedIds.encodeMount(parsed))
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
    fun invoke(toolContext: ToolContext): TransportAckResponse {
        touchActivity(toolContext, activity, "dismount")
        val agent = AgentContextHolder.current()
        val command = EnvironmentCommand.DismountTransport(agent = agent)
        val appliesAtTick = world.submit(command, appliesAtTick = engine.currentTick() + 1)
        return TransportAckResponse.queued(command.commandId, appliesAtTick, target = null)
    }
}

internal data class TransportAckResponse(
    val kind: CommandAckKind,
    val target: String? = null,
    val commandId: UUID? = null,
    val appliesAtTick: Long? = null,
    val reason: String? = null,
    val detail: String? = null,
) {
    companion object {
        fun queued(commandId: UUID, appliesAtTick: Long, target: String?): TransportAckResponse =
            TransportAckResponse(
                kind = CommandAckKind.QUEUED,
                target = target,
                commandId = commandId,
                appliesAtTick = appliesAtTick,
            )

        fun rejected(target: String?, reason: String, detail: String? = null): TransportAckResponse =
            TransportAckResponse(
                kind = CommandAckKind.REJECTED,
                target = target,
                reason = reason,
                detail = detail,
            )
    }
}
