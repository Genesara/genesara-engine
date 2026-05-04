package dev.gvart.genesara.api.internal.mcp.tools.attributes

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityTracker
import dev.gvart.genesara.api.internal.mcp.presence.touchActivity
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.player.AllocateAttributesOutcome
import dev.gvart.genesara.player.events.AgentEvent
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.annotation.Tool
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component

@Component
internal class AllocatePointsTool(
    private val registry: AgentRegistry,
    private val activity: AgentActivityTracker,
    private val publisher: ApplicationEventPublisher,
    private val tickClock: TickClock,
) {

    @Tool(
        name = "allocate_points",
        description = "Permanently spend unspent attribute points to raise attributes. IRREVERSIBLE. Validates the sum of deltas against the agent's `unspentAttributePoints` pool, atomically applies all deltas, and recomputes derived pools (maxHp, maxStamina, maxMana). Current HP/Stamina/Mana values are NOT auto-restored. Crossing 50, 100, or 200 in any attribute fires an AttributeMilestoneReached event.",
    )
    fun invoke(req: AllocatePointsRequest, toolContext: ToolContext): AllocatePointsResponse {
        touchActivity(toolContext, activity, "allocate_points")
        val agent = AgentContextHolder.current()

        return when (val outcome = registry.allocateAttributes(agent, req.deltas)) {
            null -> AllocatePointsResponse.rejected(
                reason = AllocatePointsRejectionReason.AGENT_MISSING,
                detail = "agent ${agent.id} not found in the registry",
            )
            AllocateAttributesOutcome.NegativeDelta -> AllocatePointsResponse.rejected(
                reason = AllocatePointsRejectionReason.NEGATIVE_DELTA,
                detail = "deltas must be >= 0",
            )
            is AllocateAttributesOutcome.InsufficientPoints -> AllocatePointsResponse.rejected(
                reason = AllocatePointsRejectionReason.INSUFFICIENT_POINTS,
                detail = "requested ${outcome.requested} but only ${outcome.unspent} unspent",
            )
            is AllocateAttributesOutcome.Allocated -> {
                val tick = tickClock.currentTick()
                outcome.crossedMilestones.forEach { crossing ->
                    publisher.publishEvent(
                        AgentEvent.AttributeMilestoneReached(
                            agent = agent,
                            attribute = crossing.attribute,
                            milestone = crossing.milestone,
                            tick = tick,
                        ),
                    )
                }
                AllocatePointsResponse.ok(
                    attrs = outcome.attributes,
                    remainingUnspent = outcome.remainingUnspent,
                    crossings = outcome.crossedMilestones,
                )
            }
        }
    }
}
