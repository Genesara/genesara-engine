package dev.gvart.genesara.api.internal.mcp.tools.say

import dev.gvart.genesara.api.internal.mcp.tools.CommandAckKind
import dev.gvart.genesara.world.SayChannel
import dev.gvart.genesara.world.SpeechMode
import java.util.UUID

data class SayResponse(
    val kind: CommandAckKind,
    val mode: SpeechMode,
    val channel: SayChannel,
    val commandId: UUID? = null,
    val appliesAtTick: Long? = null,
) {
    companion object {
        fun queued(commandId: UUID, appliesAtTick: Long, mode: SpeechMode, channel: SayChannel) =
            SayResponse(CommandAckKind.QUEUED, mode, channel, commandId, appliesAtTick)
    }
}
