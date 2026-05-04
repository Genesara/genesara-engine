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
        description = "Permanently spend unspent attribute points to raise attributes. IRREVERSIBLE — there is no respec. Validates the sum of deltas against the agent's `unspentAttributePoints` pool, atomically applies all deltas, and recomputes derived pools (maxHp, maxStamina, maxMana). Current HP/Stamina/Mana values are NOT auto-restored. Crossing 50, 100, or 200 in any attribute fires an AttributeMilestoneReached event.",
    )
    fun invoke(req: AllocatePointsRequest, toolContext: ToolContext): AllocatePointsResponse {
        touchActivity(toolContext, activity, "allocate_points")
        val agent = AgentContextHolder.current()

        // Negative-delta check has to run before sum-zero, otherwise a request like
        // `{STR: 2, LUCK: -2}` would be rejected as `no_points_requested` instead of
        // the more accurate `negative_delta`.
        if (req.deltas.values.any { it < 0 }) {
            return AllocatePointsResponse.rejected(
                reason = "negative_delta",
                detail = "all deltas must be >= 0; no respec in v1",
            )
        }
        if (req.deltas.values.sum() == 0) {
            return AllocatePointsResponse.rejected(
                reason = "no_points_requested",
                detail = "deltas must include at least one positive entry",
            )
        }

        return when (val outcome = registry.allocateAttributes(agent, req.deltas)) {
            null -> AllocatePointsResponse.rejected(
                reason = "agent_missing",
                detail = "agent ${agent.id} not found in the registry",
            )
            AllocateAttributesOutcome.NegativeDelta -> AllocatePointsResponse.rejected(
                reason = "negative_delta",
                detail = "all deltas must be >= 0; no respec in v1",
            )
            is AllocateAttributesOutcome.InsufficientPoints -> AllocatePointsResponse.rejected(
                reason = "insufficient_points",
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
