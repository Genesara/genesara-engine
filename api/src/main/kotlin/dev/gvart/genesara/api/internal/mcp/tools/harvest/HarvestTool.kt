package dev.gvart.genesara.api.internal.mcp.tools.harvest

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityTracker
import dev.gvart.genesara.api.internal.mcp.presence.touchActivity
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.AgentPlotsStore
import dev.gvart.genesara.world.CropLookup
import dev.gvart.genesara.world.ResourceItemId
import dev.gvart.genesara.world.WorldCommandGateway
import dev.gvart.genesara.world.WorldQueryGateway
import dev.gvart.genesara.world.commands.WorldCommand
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component

@Component
internal class HarvestTool(
    private val world: WorldCommandGateway,
    private val engine: TickClock,
    private val activity: AgentActivityTracker,
    private val query: WorldQueryGateway,
    private val plots: AgentPlotsStore,
    private val crops: CropLookup,
) {

    @Tool(
        name = "harvest",
        description = "Extract one of the resources currently present at the agent's node. " +
            "Resource availability is per-node and rolled at world generation — call `look_around` first " +
            "and pick an itemId from `current.resources`; harvesting an item the node does not stock is " +
            "rejected with ResourceNotAvailableHere. Queues a Harvest command; the resulting " +
            "ResourceHarvested event arrives on the agent's event stream once the tick lands. Costs stamina. " +
            "If a ripe FARM_PLOT crop on this node yields the requested itemId, harvests the plot " +
            "instead of the resource cell — emits CropHarvested rather than ResourceHarvested.",
    )
    fun invoke(
        @ToolParam(
            required = true,
            description = "Resource id available at the agent's current node. Read it from " +
                "`look_around().current.resources` — do not guess. Harvesting an item the node " +
                "does not stock is rejected with ResourceNotAvailableHere. A ripe FARM_PLOT " +
                "crop whose output matches this id takes precedence over the resource cell.",
        )
        itemId: ResourceItemId,
        toolContext: ToolContext,
    ): HarvestResponse {
        touchActivity(toolContext, activity, "harvest")
        val agent = AgentContextHolder.current()
        val ripePlot = ripePlotMatching(agent, itemId)
        val command: WorldCommand = if (ripePlot != null) {
            WorldCommand.HarvestCrop(agent = agent, plotId = ripePlot)
        } else {
            WorldCommand.Harvest(agent = agent, item = itemId.toItemId())
        }
        val appliesAtTick = world.submit(command, appliesAtTick = engine.currentTick() + 1)
        return HarvestResponse.queued(command.commandId, appliesAtTick, itemId.name)
    }

    private fun ripePlotMatching(agent: AgentId, itemId: ResourceItemId): java.util.UUID? {
        val nodeId = query.activePositionOf(agent) ?: return null
        val tick = query.currentTickFor(agent)
        val target = itemId.toItemId()
        val plotsAtNode = plots.listByNodes(setOf(nodeId))[nodeId].orEmpty()
        return plotsAtNode.firstNotNullOfOrNull { plot ->
            val plant = plot.plant ?: return@firstNotNullOfOrNull null
            val crop = crops.byId(plant.cropId) ?: return@firstNotNullOfOrNull null
            if (crop.outputItem != target) return@firstNotNullOfOrNull null
            val ripeAt = plant.plantedAtTick + crop.ticksToRipe
            if (tick < ripeAt) null else plot.plotId
        }
    }
}
