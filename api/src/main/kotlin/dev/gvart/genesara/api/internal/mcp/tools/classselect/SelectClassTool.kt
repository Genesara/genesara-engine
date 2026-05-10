package dev.gvart.genesara.api.internal.mcp.tools.classselect

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityTracker
import dev.gvart.genesara.api.internal.mcp.presence.touchActivity
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.player.AgentClass
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.player.AssignClassOutcome
import dev.gvart.genesara.player.events.AgentEvent
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component

@Component
internal class SelectClassTool(
    private val agents: AgentRegistry,
    private val tickClock: TickClock,
    private val publisher: ApplicationEventPublisher,
    private val activity: AgentActivityTracker,
) {

    @Tool(
        name = "select_class",
        description = "Permanently commit to one of the two classes offered by the level-10 " +
            "ClassChoiceOffered event. IRREVERSIBLE — the class stays for the agent's lifetime; " +
            "no respec. Pending offers come from ClassChoiceOffered events and are also surfaced " +
            "as `pendingClassChoice` on get_status. Validates: the class id decodes, the agent " +
            "has no class yet, and the chosen class is one of the two pending candidates.",
    )
    fun invoke(
        @ToolParam(
            required = true,
            description = "Class id chosen from the ClassChoiceOffered event candidates " +
                "(e.g. SOLDIER, SCOUT, RESEARCHER).",
        )
        classId: AgentClass,
        toolContext: ToolContext,
    ): SelectClassResponse {
        touchActivity(toolContext, activity, "select_class")
        val agent = AgentContextHolder.current()

        return when (val outcome = agents.assignClass(agent, classId)) {
            AssignClassOutcome.Assigned -> {
                val tick = tickClock.currentTick()
                publisher.publishEvent(AgentEvent.ClassChosen(agent = agent, classId = classId, tick = tick))
                SelectClassResponse.ok(classId)
            }

            is AssignClassOutcome.AlreadyClassed -> SelectClassResponse.rejected(
                classId = classId,
                reason = "already_classed",
                detail = "Agent is already ${outcome.existing.name} (class is forever, no respec).",
            )

            AssignClassOutcome.NoPendingOffer -> SelectClassResponse.rejected(
                classId = classId,
                reason = "no_pending_offer",
                detail = "No pending class-choice offer — reach level 10 first.",
            )

            is AssignClassOutcome.NotInOffer -> SelectClassResponse.rejected(
                classId = classId,
                reason = "not_offered",
                detail = "Pending offer is ${outcome.pending.first.name} or ${outcome.pending.second.name}; " +
                    "${classId.name} is not one of them.",
            )

            AssignClassOutcome.UnknownAgent -> SelectClassResponse.rejected(
                classId = classId,
                reason = "unknown_agent",
                detail = "Agent ${agent.id} not found.",
            )
        }
    }
}
