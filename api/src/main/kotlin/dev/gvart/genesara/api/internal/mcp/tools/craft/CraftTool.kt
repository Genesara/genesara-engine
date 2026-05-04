package dev.gvart.genesara.api.internal.mcp.tools.craft

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityTracker
import dev.gvart.genesara.api.internal.mcp.presence.touchActivity
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.world.RecipeId
import dev.gvart.genesara.world.WorldCommandGateway
import dev.gvart.genesara.world.commands.WorldCommand
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.annotation.Tool
import org.springframework.stereotype.Component

@Component
internal class CraftTool(
    private val world: WorldCommandGateway,
    private val engine: TickClock,
    private val activity: AgentActivityTracker,
) {

    @Tool(
        name = "craft",
        description = "Craft an item from a known recipe at the agent's current node. Requires a " +
            "matching crafting station active on the node, recipe inputs in inventory, the recipe's " +
            "required skill level, and stamina. Output rarity is rolled from the agent's skill + Luck. " +
            "Queues a CraftItem command; the resulting ItemCrafted event arrives on the agent's event " +
            "stream once the tick lands.",
    )
    fun invoke(req: CraftRequest, toolContext: ToolContext): CraftResponse {
        touchActivity(toolContext, activity, "craft")
        val agent = AgentContextHolder.current()
        val command = WorldCommand.CraftItem(agent = agent, recipe = RecipeId(req.recipeId))
        val nextTick = engine.currentTick() + 1
        world.submit(command, appliesAtTick = nextTick)
        return CraftResponse(commandId = command.commandId, appliesAtTick = nextTick, recipeId = req.recipeId)
    }
}
