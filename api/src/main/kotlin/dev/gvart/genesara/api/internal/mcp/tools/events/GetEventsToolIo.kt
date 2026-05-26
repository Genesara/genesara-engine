package dev.gvart.genesara.api.internal.mcp.tools.events

import tools.jackson.databind.JsonNode

data class GetEventsOutput(
    val events: List<EventView>,
    val count: Int,
)

data class EventView(
    val seq: Long,
    val type: String,
    val tick: Long,
    val payload: JsonNode,
)
