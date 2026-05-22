package dev.gvart.genesara.api.internal.mcp.tools.transport

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityTracker
import dev.gvart.genesara.api.internal.mcp.presence.touchActivity
import dev.gvart.genesara.api.internal.mcp.tools.PrefixedIds
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.world.WorldCommandGateway
import dev.gvart.genesara.world.commands.EnvironmentCommand
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component

@Component
internal class TameTool(
    private val world: WorldCommandGateway,
    private val engine: TickClock,
    private val activity: AgentActivityTracker,
) {

    @Tool(
        name = "tame",
        description = "Attempt to tame a Tier-A NPC into a personal mount. The target id must be wire-prefixed " +
            "as `npc:<uuid>` exactly as returned by `look_around` / `inspect_npc`. Costs stamina regardless of " +
            "outcome. ANIMAL_HANDLING + Luck modulate success odds; failure may spook the creature into fleeing " +
            "an adjacent node. Resolves on the next tick via a MountTamed or MountTameFailed event. Rejected if " +
            "you're already mounted, the NPC isn't a tameable species, you're not at the same node, or you've " +
            "hit your mount cap (cap = 1 + ANIMAL_HANDLING/50, max 4).",
    )
    fun invoke(
        @ToolParam(required = true, description = "Wire-prefixed target id — must be `npc:<uuid>`.")
        target: String,
        toolContext: ToolContext,
    ): TameResponse {
        touchActivity(toolContext, activity, "tame")
        val parsed = PrefixedIds.parseNpc(target)
            ?: return TameResponse.rejected(
                target = target,
                reason = "bad_target_id",
                detail = "target must be npc:<uuid>",
            )
        val agent = AgentContextHolder.current()
        val command = EnvironmentCommand.Tame(agent = agent, target = parsed)
        val appliesAtTick = world.submit(command, appliesAtTick = engine.currentTick() + 1)
        return TameResponse.queued(command.commandId, appliesAtTick, PrefixedIds.encodeNpc(parsed))
    }
}
