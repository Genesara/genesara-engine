package dev.gvart.genesara.api.internal.mcp.tools.attack

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityTracker
import dev.gvart.genesara.api.internal.mcp.presence.touchActivity
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.world.NpcId
import dev.gvart.genesara.world.WorldCommandGateway
import dev.gvart.genesara.world.commands.WorldCommand
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component
import java.util.UUID

@Component
internal class AttackNpcTool(
    private val world: WorldCommandGateway,
    private val engine: TickClock,
    private val activity: AgentActivityTracker,
) {

    @Tool(
        name = "attack_npc",
        description = "Attack a Tier-A NPC (fauna) within your weapon's range — same node for melee, " +
            "adjacent or further nodes for ranged. Queues an AttackNpc command; the resulting " +
            "AgentAttackedNpc + (on kill) NpcDied + ItemDroppedOnGround events arrive on your stream " +
            "once the tick lands. Costs stamina; rejected if the NPC is unknown (out of active range, " +
            "or already dead), or out of weapon range.",
    )
    fun invoke(
        @ToolParam(required = true, description = "Target NPC — UUID returned by `look_around` or `inspect_npc`.")
        npcId: String,
        toolContext: ToolContext,
    ): AttackNpcResponse {
        touchActivity(toolContext, activity, "attack_npc")
        val npcUuid = runCatching { UUID.fromString(npcId) }.getOrNull()
            ?: return AttackNpcResponse.rejected(
                npcId = npcId,
                reason = "bad_npc_id",
                detail = "npcId must be a UUID",
            )
        val agent = AgentContextHolder.current()
        val command = WorldCommand.AttackNpc(agent = agent, npc = NpcId(npcUuid))
        val appliesAtTick = world.submit(command, appliesAtTick = engine.currentTick() + 1)
        return AttackNpcResponse.queued(command.commandId, appliesAtTick, npcUuid)
    }
}
