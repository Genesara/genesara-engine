package dev.gvart.genesara.api.internal.mcp.tools.events

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.events.AgentEventLog
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityTracker
import dev.gvart.genesara.api.internal.mcp.presence.touchActivity
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component

@Component
internal class GetEventsTool(
    private val log: AgentEventLog,
    private val activity: AgentActivityTracker,
) {

    @Tool(
        name = "get_events",
        description = "Read events from the caller's personal event stream. Events include party invites, " +
            "trade offers, inbound NPC attacks, say broadcasts, skill recommendations, combat outcomes, " +
            "and command rejections. Use `since` to paginate: pass the last-seen `seq` and only newer " +
            "events are returned. Use `types` to filter, e.g. [\"party.invite_received\", \"trade.offer_received\"]. " +
            "Returns up to `limit` events (max 200) in ascending seq order.",
    )
    fun invoke(
        @ToolParam(description = "Return events with seq strictly greater than this value. Omit (or pass 0) to start from the oldest retained event.", required = false)
        since: Long?,
        @ToolParam(description = "Maximum number of events to return. Capped at 200. Defaults to 50.", required = false)
        limit: Int?,
        @ToolParam(description = "Optional list of event type strings to include. When absent all types are returned.", required = false)
        types: List<String>?,
        toolContext: ToolContext,
    ): GetEventsOutput {
        touchActivity(toolContext, activity, "get_events")
        val agentId = AgentContextHolder.current()
        val effectiveLimit = (limit ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        val after = since ?: 0L

        val events = log.since(agentId, after)
            .let { all -> if (types.isNullOrEmpty()) all else all.filter { it.type in types } }
            .takeLast(effectiveLimit)

        return GetEventsOutput(
            events = events.map { e ->
                EventView(seq = e.seq, type = e.type, tick = e.tick, payload = e.payload)
            },
            count = events.size,
        )
    }

    companion object {
        private const val DEFAULT_LIMIT = 50
        private const val MAX_LIMIT = 200
    }
}
