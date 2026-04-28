package dev.gvart.agenticrpg.api.internal.mcp.tools.spawn

import com.fasterxml.jackson.annotation.JsonClassDescription
import java.util.UUID

@JsonClassDescription("Login: enter the world. Resumes at last node, or picks a random one for first-time agents.")
class SpawnRequest

/**
 * Response shape for `spawn`.
 *
 * - `kind = "queued"`: a SpawnAgent command was queued; the agent appears at `at` on `appliesAtTick`.
 * - `kind = "already_present"`: the agent is already in the world at `at`; no command was queued.
 */
data class SpawnResponse(
    val kind: String,
    val at: Long,
    val commandId: UUID? = null,
    val appliesAtTick: Long? = null,
) {
    companion object {
        fun queued(commandId: UUID, appliesAtTick: Long, at: Long) =
            SpawnResponse("queued", at, commandId, appliesAtTick)

        fun alreadyPresent(at: Long) =
            SpawnResponse("already_present", at)
    }
}
