package dev.gvart.genesara.api.internal.mcp.tools.abilities

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityTracker
import dev.gvart.genesara.api.internal.mcp.presence.touchActivity
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.player.AbilityId
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.WorldCommandGateway
import dev.gvart.genesara.world.commands.WorldCommand
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component
import java.util.UUID

@Component
internal class UseAbilityTool(
    private val world: WorldCommandGateway,
    private val engine: TickClock,
    private val activity: AgentActivityTracker,
) {

    @Tool(
        name = "use_ability",
        description = "Cast an active ability granted by a chosen perk on a slotted skill (e.g. SWORD_POWER_STRIKE). " +
            "Pays the perk's resource cost (HP / Stamina / Mana) at cast and arms the perk cooldown. " +
            "`targetAgentId` is required for SINGLE_AGENT abilities (target must be in the caster's node) " +
            "and must be omitted for SELF / AREA_SELF_NODE abilities. Reducer-level rejections — unknown " +
            "ability, on cooldown, insufficient resource, target shape mismatch — surface as " +
            "CommandRejected on the agent's event stream after the tick lands.",
    )
    fun invoke(
        @ToolParam(required = true, description = "Ability id (e.g. SWORD_POWER_STRIKE) granted by a chosen perk.")
        abilityId: String,
        @ToolParam(required = false, description = "Target agent id for SINGLE_AGENT abilities; omit for SELF / AREA_SELF_NODE.")
        targetAgentId: UUID?,
        toolContext: ToolContext,
    ): UseAbilityResponse {
        touchActivity(toolContext, activity, "use_ability")
        val agent = AgentContextHolder.current()
        val command = WorldCommand.UseAbility(
            agent = agent,
            ability = AbilityId(abilityId),
            target = targetAgentId?.let(::AgentId),
        )
        val nextTick = engine.currentTick() + 1
        world.submit(command, appliesAtTick = nextTick)
        return UseAbilityResponse.queued(command.commandId, nextTick, abilityId, targetAgentId)
    }
}
