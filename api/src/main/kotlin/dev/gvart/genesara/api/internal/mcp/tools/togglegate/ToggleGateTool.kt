package dev.gvart.genesara.api.internal.mcp.tools.togglegate

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityTracker
import dev.gvart.genesara.api.internal.mcp.presence.touchActivity
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.world.WorldCommandGateway
import dev.gvart.genesara.world.commands.WorldCommand
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component
import java.util.UUID

@Component
internal class ToggleGateTool(
    private val world: WorldCommandGateway,
    private val engine: TickClock,
    private val activity: AgentActivityTracker,
) {

    @Tool(
        name = "toggle_gate",
        description = "Flip the OPEN/CLOSED state of a GATE building at the agent's current node. " +
            "Requires the agent to be standing on the gate's node AND to hold a matching GATE_KEY " +
            "in inventory. Passage through a CLOSED gate is blocked (DefensiveBlocks); passage " +
            "through an OPEN gate is permitted to anyone — toggle with intent. Emits GateToggled.",
    )
    fun invoke(
        @ToolParam(
            required = true,
            description = "Building instance id of the GATE to flip. Read it from `look_around().current.buildings` " +
                "or the GateKeyMinted event that issued your key.",
        )
        gateId: String,
        toolContext: ToolContext,
    ): ToggleGateResponse {
        touchActivity(toolContext, activity, "toggle_gate")
        val gateUuid = runCatching { UUID.fromString(gateId) }.getOrNull()
            ?: return ToggleGateResponse.rejected(gateId, "bad_gate_id", "gateId must be a UUID")
        val agent = AgentContextHolder.current()
        val command = WorldCommand.ToggleGate(agent = agent, gateId = gateUuid)
        val appliesAtTick = world.submit(command, appliesAtTick = engine.currentTick() + 1)
        return ToggleGateResponse.queued(command.commandId, appliesAtTick, gateUuid)
    }
}
