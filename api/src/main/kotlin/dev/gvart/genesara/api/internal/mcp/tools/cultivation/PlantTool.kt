package dev.gvart.genesara.api.internal.mcp.tools.cultivation

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityTracker
import dev.gvart.genesara.api.internal.mcp.presence.touchActivity
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.world.CropId
import dev.gvart.genesara.world.WorldCommandGateway
import dev.gvart.genesara.world.commands.WorldCommand
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component
import java.util.UUID

@Component
internal class PlantTool(
    private val world: WorldCommandGateway,
    private val engine: TickClock,
    private val activity: AgentActivityTracker,
) {

    @Tool(
        name = "plant",
        description = "Sow a crop in a FARM_PLOT building you own. Find plotId via " +
            "`look_around().current.buildings[].plotId` (only present on FARM_PLOT entries). " +
            "Find cropId in the cultivation catalog (e.g. `WHEAT`, `MEDICINAL_HERB`). The plot " +
            "must be empty, the terrain must admit the crop, your FARMING level must meet the " +
            "crop's gate, and your inventory must carry the crop's seed item. Costs stamina; " +
            "the resulting CropPlanted event arrives on your event stream once the tick lands.",
    )
    fun invoke(
        @ToolParam(required = true, description = "FARM_PLOT plot id from `look_around().current.buildings[].plotId`.")
        plotId: String,
        @ToolParam(required = true, description = "Crop catalog id to plant (e.g. WHEAT, MEDICINAL_HERB).")
        cropId: String,
        toolContext: ToolContext,
    ): PlantResponse {
        touchActivity(toolContext, activity, "plant")
        val agent = AgentContextHolder.current()
        val command = WorldCommand.PlantCrop(
            agent = agent,
            plotId = UUID.fromString(plotId),
            crop = CropId(cropId),
        )
        val appliesAtTick = world.submit(command, appliesAtTick = engine.currentTick() + 1)
        return PlantResponse.queued(command.commandId, appliesAtTick, plotId, cropId)
    }
}
