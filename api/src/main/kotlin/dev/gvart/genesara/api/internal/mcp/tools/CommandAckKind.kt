package dev.gvart.genesara.api.internal.mcp.tools

/**
 * Discriminator for queue-and-ack tool responses. The MCP `@Tool` returns
 * synchronously the moment the command is enqueued (before the tick has
 * resolved it), so the only outcome it can report in-band is "queued".
 * Reducer-level rejections surface later via `WorldEvent.CommandRejected`
 * on the agent's event stream, not on this response.
 *
 * Tools whose synchronous response itself carries success/failure semantics
 * (e.g. `equip_item`'s `equipped` / `rejected`, `allocate_points`'
 * `OK` / `REJECTED`) keep their own enums — they're describing a different
 * shape (in-band sync outcome, not an enqueue ack).
 */
enum class CommandAckKind { QUEUED }
