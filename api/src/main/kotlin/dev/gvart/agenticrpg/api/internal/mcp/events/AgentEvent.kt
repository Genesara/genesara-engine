package dev.gvart.agenticrpg.api.internal.mcp.events

import tools.jackson.databind.JsonNode
import java.util.UUID

/**
 * Envelope for an event delivered over the per-agent MCP resource `agent://{id}/events`.
 *
 * - [id]      unique event id (UUID v4)
 * - [seq]     monotonic per-agent sequence; clients pass the last-seen value as `?after=N` to
 *             resume a non-destructive read across reconnects
 * - [type]    stable string discriminator (e.g. "agent.moved", "agent.spawned")
 * - [tick]    server tick at which the underlying event occurred
 * - [payload] the raw world event as JSON (includes `causedBy` for action correlation)
 */
internal data class AgentEvent(
    val id: UUID,
    val seq: Long,
    val type: String,
    val tick: Long,
    val payload: JsonNode,
)
