package dev.gvart.genesara.api.internal.mcp.tools.craft

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityTracker
import dev.gvart.genesara.api.internal.mcp.presence.touchActivity
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.world.RecipeId
import dev.gvart.genesara.world.WorldCommandGateway
import dev.gvart.genesara.world.commands.EconomyCommand
import java.util.UUID
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
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
            "stream once the tick lands. " +
            "Some recipes require an existing per-instance item as a `source` template — e.g. " +
            "GATE_KEY_COPY duplicates an existing GATE_KEY (the source stays; the new key inherits " +
            "the source's gate-binding).",
    )
    fun invoke(
        @ToolParam(required = true, description = "Recipe id to craft at the agent's current node.")
        recipeId: String,
        @ToolParam(
            required = false,
            description = "Instance UUID of an existing per-instance item the recipe operates on. " +
                "Required only when the recipe declares `requiresSource` (e.g. GATE_KEY_COPY needs " +
                "an existing GATE_KEY id). Omit for plain craft recipes.",
        )
        source: String? = null,
        toolContext: ToolContext,
    ): CraftResponse {
        touchActivity(toolContext, activity, "craft")
        val sourceUuid = if (source == null) null else
            runCatching { UUID.fromString(source) }.getOrNull()
                ?: return CraftResponse.rejected(recipeId, "bad_source_id", "source must be a UUID")
        val agent = AgentContextHolder.current()
        val command = EconomyCommand.CraftItem(agent = agent, recipe = RecipeId(recipeId), source = sourceUuid)
        val appliesAtTick = world.submit(command, appliesAtTick = engine.currentTick() + 1)
        return CraftResponse.queued(command.commandId, appliesAtTick, recipeId)
    }
}
