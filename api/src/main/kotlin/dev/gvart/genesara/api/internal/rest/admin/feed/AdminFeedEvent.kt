package dev.gvart.genesara.api.internal.rest.admin.feed

import tools.jackson.databind.JsonNode
import java.util.UUID

/**
 * Envelope for one entry on the god-view `admin:feed`. [agent] and [node] are extracted
 * from the underlying world/agent event at append time so server-side filtering does not
 * re-parse the payload on every read.
 */
internal data class AdminFeedEvent(
    val id: UUID,
    val seq: Long,
    val type: String,
    val tick: Long,
    val agent: UUID?,
    val node: Long?,
    val payload: JsonNode,
)
