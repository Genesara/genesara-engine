package dev.gvart.genesara.api.internal.mcp.tools.attributes

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityTracker
import dev.gvart.genesara.api.internal.mcp.presence.touchActivity
import dev.gvart.genesara.api.internal.mcp.tools.equipment.DerivedPoolsRefresher
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.player.AllocateAttributesOutcome
import dev.gvart.genesara.player.Attribute
import dev.gvart.genesara.player.events.AgentEvent
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component

@Component
internal class AllocatePointsTool(
    private val registry: AgentRegistry,
    private val activity: AgentActivityTracker,
    private val publisher: ApplicationEventPublisher,
    private val tickClock: TickClock,
    private val derivedPools: DerivedPoolsRefresher,
) {

    @Tool(
        name = "allocate_points",
        description = "Permanently spend unspent attribute points to raise attributes. IRREVERSIBLE. Validates the sum of deltas against the agent's `unspentAttributePoints` pool, atomically applies all deltas, and recomputes derived pools (maxHp, maxStamina, maxMana). Current HP/Stamina/Mana values are NOT auto-restored. Crossing 50, 100, or 200 in any attribute fires an AttributeMilestoneReached event.",
    )
    fun invoke(
        @ToolParam(required = true, description = "Map of attribute (STRENGTH, DEXTERITY, CONSTITUTION, PERCEPTION, INTELLIGENCE, LUCK) to non-negative points to spend; sum must be ≤ unspentAttributePoints.")
        deltas: Map<Attribute, Int>,
        toolContext: ToolContext,
    ): AllocatePointsResponse {
        touchActivity(toolContext, activity, "allocate_points")
        val agent = AgentContextHolder.current()

        if (deltas.isEmpty() || deltas.values.all { it == 0 }) {
            return AllocatePointsResponse.rejected(
                reason = AllocatePointsRejectionReason.NO_OP,
                detail = "deltas are empty or every entry is zero — nothing to allocate",
            )
        }

        return when (val outcome = registry.allocateAttributes(agent, deltas)) {
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
                // Recompute pools through the shared refresher so the equipment-derived
                // component (Slice 4) isn't wiped by a stale base-only computation. The
                // registry has already been updated with the new base attributes; the
                // refresher reads them and layers attribute bonuses on top.
                derivedPools.refresh(agent)
                AllocatePointsResponse.ok(
                    attrs = outcome.attributes,
                    remainingUnspent = outcome.remainingUnspent,
                    crossings = outcome.crossedMilestones,
                )
            }
        }
    }
}
