package dev.gvart.genesara.api.internal.mcp.tools.classselect

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityTracker
import dev.gvart.genesara.api.internal.mcp.presence.touchActivity
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.player.AgentClass
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.player.AssignEvolutionOutcome
import dev.gvart.genesara.player.events.AgentEvent
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component

@Component
internal class SelectEvolutionTool(
    private val agents: AgentRegistry,
    private val tickClock: TickClock,
    private val publisher: ApplicationEventPublisher,
    private val activity: AgentActivityTracker,
) {

    @Tool(
        name = "select_evolution",
        description = "Permanently commit to one of the two evolution classes offered by the " +
            "level-50 EvolutionChoiceOffered event. IRREVERSIBLE — the evolution overwrites the " +
            "agent's class for the rest of its lifetime; no respec, no cross-tree pivot. Pending " +
            "offers come from EvolutionChoiceOffered events and are also surfaced as " +
            "`pendingEvolutionChoice` on get_status. Validates: the class id decodes, the agent " +
            "is on a base class, and the chosen class is one of the two pending candidates.",
    )
    fun invoke(
        @ToolParam(
            required = true,
            description = "Evolution class id chosen from the EvolutionChoiceOffered event " +
                "candidates (e.g. HEAVY_SOLDIER, RANGER, SCHOLAR).",
        )
        classId: AgentClass,
        toolContext: ToolContext,
    ): SelectEvolutionResponse {
        touchActivity(toolContext, activity, "select_evolution")
        val agent = AgentContextHolder.current()

        return when (val outcome = agents.assignEvolution(agent, classId)) {
            is AssignEvolutionOutcome.Assigned -> {
                val tick = tickClock.currentTick()
                publisher.publishEvent(
                    AgentEvent.ClassEvolved(
                        agent = agent,
                        fromClass = outcome.from,
                        toClass = outcome.to,
                        tick = tick,
                    )
                )
                SelectEvolutionResponse.ok(from = outcome.from, to = outcome.to)
            }

            AssignEvolutionOutcome.NoClassAssigned -> SelectEvolutionResponse.rejected(
                classId = classId,
                reason = "no_class_assigned",
                detail = "Agent has no class yet — pick a base class via select_class first.",
            )

            is AssignEvolutionOutcome.AlreadyEvolved -> SelectEvolutionResponse.rejected(
                classId = classId,
                reason = "already_evolved",
                detail = "Agent is already evolved into ${outcome.existing.name} (evolution is forever, no respec).",
            )

            AssignEvolutionOutcome.NoPendingOffer -> SelectEvolutionResponse.rejected(
                classId = classId,
                reason = "no_pending_offer",
                detail = "No pending evolution-choice offer — reach level 50 first.",
            )

            is AssignEvolutionOutcome.NotInOffer -> SelectEvolutionResponse.rejected(
                classId = classId,
                reason = "not_offered",
                detail = "Pending offer is ${outcome.pending.first.name} or ${outcome.pending.second.name}; " +
                    "${classId.name} is not one of them.",
            )

            is AssignEvolutionOutcome.WrongParent -> SelectEvolutionResponse.rejected(
                classId = classId,
                reason = "wrong_parent",
                detail = "${classId.name} is an evolution of ${outcome.actualParent ?: "no class"}; " +
                    "the agent's class is ${outcome.expectedParent.name}. Cross-tree evolution is forbidden.",
            )

            AssignEvolutionOutcome.UnknownAgent -> SelectEvolutionResponse.rejected(
                classId = classId,
                reason = "unknown_agent",
                detail = "Agent ${agent.id} not found.",
            )
        }
    }
}
