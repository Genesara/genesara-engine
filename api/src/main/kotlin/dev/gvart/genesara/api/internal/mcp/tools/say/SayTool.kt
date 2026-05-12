package dev.gvart.genesara.api.internal.mcp.tools.say

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityTracker
import dev.gvart.genesara.api.internal.mcp.presence.touchActivity
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.world.SayChannel
import dev.gvart.genesara.world.SpeechMode
import dev.gvart.genesara.world.WorldCommandGateway
import dev.gvart.genesara.world.commands.WorldCommand
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component

@Component
internal class SayTool(
    private val world: WorldCommandGateway,
    private val engine: TickClock,
    private val activity: AgentActivityTracker,
) {

    @Tool(
        name = "say",
        description = "Speak aloud. Every agent within node-hop range hears you and receives an " +
            "agent.spoke event — including yourself. Volume sets the reach: WHISPER (1 hop), " +
            "NORMAL (3 hops, default), SCREAM (5 hops). Channel is LOCAL in v1. Queues a Say " +
            "command; the agent.spoke event arrives on listeners' streams once the tick lands. " +
            "Messages longer than the balance cap are rejected with MessageTooLong.",
    )
    fun invoke(
        @ToolParam(required = true, description = "Message text to broadcast to listeners in range.")
        message: String,
        @ToolParam(required = false, description = "Volume — WHISPER (1 hop), NORMAL (3 hops, default), SCREAM (5 hops).")
        mode: SpeechMode?,
        @ToolParam(required = false, description = "Routing channel; v1 supports LOCAL only.")
        channel: SayChannel?,
        toolContext: ToolContext,
    ): SayResponse {
        touchActivity(toolContext, activity, "say")
        val resolvedMode = mode ?: SpeechMode.NORMAL
        val resolvedChannel = channel ?: SayChannel.LOCAL
        val agent = AgentContextHolder.current()
        val command = WorldCommand.Say(
            agent = agent,
            message = message,
            mode = resolvedMode,
            channel = resolvedChannel,
        )
        val appliesAtTick = world.submit(command, appliesAtTick = engine.currentTick() + 1)
        return SayResponse.queued(command.commandId, appliesAtTick, resolvedMode, resolvedChannel)
    }
}
