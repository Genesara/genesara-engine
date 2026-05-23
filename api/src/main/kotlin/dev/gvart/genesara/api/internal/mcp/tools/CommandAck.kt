package dev.gvart.genesara.api.internal.mcp.tools

import com.fasterxml.jackson.annotation.JsonInclude
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.world.WorldCommandGateway
import dev.gvart.genesara.world.commands.WorldCommand
import java.util.UUID

enum class CommandAckKind { QUEUED, REJECTED }

/**
 * Unified queue-and-ack response shape for every MCP tool that submits a
 * `WorldCommand`. `target` is the wire-prefixed id the action operates on
 * (e.g. `mount:<uuid>`, `npc:<uuid>`, `building:<uuid>`, a recipe id, an
 * ability id) — null when the verb has no target (DismountTransport).
 *
 * On QUEUED: `commandId` + `appliesAtTick` are set, `reason`/`detail` null.
 * On REJECTED: `reason` + optional `detail` are set, `commandId` null.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class CommandAckResponse(
    val kind: CommandAckKind,
    val target: String? = null,
    val commandId: UUID? = null,
    val appliesAtTick: Long? = null,
    val reason: String? = null,
    val detail: String? = null,
) {
    companion object {
        fun queued(commandId: UUID, appliesAtTick: Long, target: String? = null): CommandAckResponse =
            CommandAckResponse(
                kind = CommandAckKind.QUEUED,
                target = target,
                commandId = commandId,
                appliesAtTick = appliesAtTick,
            )

        fun rejected(target: String?, reason: String, detail: String? = null): CommandAckResponse =
            CommandAckResponse(
                kind = CommandAckKind.REJECTED,
                target = target,
                reason = reason,
                detail = detail,
            )
    }
}

/**
 * Queue [command] for `engine.currentTick() + 1` and return a [CommandAckResponse]
 * with the resulting `appliesAtTick`. Every queue-and-ack MCP tool routes
 * through this helper instead of hand-rolling the same 3-line pattern.
 */
internal fun WorldCommandGateway.submitQueued(
    command: WorldCommand,
    engine: TickClock,
    target: String? = null,
): CommandAckResponse {
    val appliesAtTick = this.submit(command, appliesAtTick = engine.currentTick() + 1)
    return CommandAckResponse.queued(command.commandId, appliesAtTick, target)
}
