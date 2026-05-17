package dev.gvart.genesara.api.internal.mcp.tools.cultivation

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityTracker
import dev.gvart.genesara.api.internal.mcp.presence.touchActivity
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.world.WorldCommandGateway
import dev.gvart.genesara.world.commands.EconomyCommand
import java.util.UUID
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component

@Component
internal class TendTool(
    private val world: WorldCommandGateway,
    private val engine: TickClock,
    private val activity: AgentActivityTracker,
) {

    @Tool(
        name = "tend",
        description = "Refresh the neglect timer on a planted FARM_PLOT you own. Find plotId via " +
            "`look_around().current.buildings[].plotId` and confirm `plantedCrop` is non-null. " +
            "If a planted plot's `last_tended_at_tick` falls behind the crop's `neglectWindowTicks` " +
            "without a tend, the per-tick neglect sweep clears it and emits CropDied. Costs stamina; " +
            "the resulting CropTended event arrives on your event stream once the tick lands.",
    )
    fun invoke(
        @ToolParam(required = true, description = "FARM_PLOT plot id from `look_around().current.buildings[].plotId`.")
        plotId: String,
        toolContext: ToolContext,
    ): TendResponse {
        touchActivity(toolContext, activity, "tend")
        val agent = AgentContextHolder.current()
        val command = EconomyCommand.TendCrop(agent = agent, plotId = UUID.fromString(plotId))
        val appliesAtTick = world.submit(command, appliesAtTick = engine.currentTick() + 1)
        return TendResponse.queued(command.commandId, appliesAtTick, plotId)
    }
}
