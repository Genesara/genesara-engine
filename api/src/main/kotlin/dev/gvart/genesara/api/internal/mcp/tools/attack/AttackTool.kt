package dev.gvart.genesara.api.internal.mcp.tools.attack

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityTracker
import dev.gvart.genesara.api.internal.mcp.presence.touchActivity
import dev.gvart.genesara.api.internal.mcp.tools.AttackTarget
import dev.gvart.genesara.api.internal.mcp.tools.PrefixedIds
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.world.WorldCommandGateway
import dev.gvart.genesara.world.commands.WorldCommand
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component

@Component
internal class AttackTool(
    private val world: WorldCommandGateway,
    private val engine: TickClock,
    private val activity: AgentActivityTracker,
) {

    @Tool(
        name = "attack",
        description = "Attack a single target — another agent or a Tier-A NPC — within your " +
            "weapon's range (same node for melee, adjacent or further nodes for ranged weapons). " +
            "The target id is wire-prefixed: `agent:<uuid>` for players, `npc:<uuid>` for fauna. " +
            "Pass the id verbatim as returned by `look_around` / `inspect` / `inspect_npc`. Queues " +
            "the matching attack command; the resolution event (AgentAttacked or AgentAttackedNpc) " +
            "arrives on the event stream once the tick lands. Costs stamina; rejected if the target " +
            "is beyond range, not in the world, or already dead.",
    )
    fun invoke(
        @ToolParam(
            required = true,
            description = "Wire-prefixed target id — `agent:<uuid>` or `npc:<uuid>`.",
        )
        target: String,
        toolContext: ToolContext,
    ): AttackResponse {
        touchActivity(toolContext, activity, "attack")
        val parsed = PrefixedIds.parseAttackTarget(target)
            ?: return AttackResponse.rejected(
                target = target,
                reason = "bad_target_id",
                detail = "target must be agent:<uuid> or npc:<uuid>",
            )
        val agent = AgentContextHolder.current()
        val command = when (parsed) {
            is AttackTarget.Agent -> WorldCommand.AttackTarget(agent = agent, target = parsed.id)
            is AttackTarget.Npc -> WorldCommand.AttackNpc(agent = agent, npc = parsed.id)
        }
        val appliesAtTick = world.submit(command, appliesAtTick = engine.currentTick() + 1)
        val echoed = when (parsed) {
            is AttackTarget.Agent -> PrefixedIds.encodeAgent(parsed.id)
            is AttackTarget.Npc -> PrefixedIds.encodeNpc(parsed.id)
        }
        return AttackResponse.queued(command.commandId, appliesAtTick, echoed)
    }
}
