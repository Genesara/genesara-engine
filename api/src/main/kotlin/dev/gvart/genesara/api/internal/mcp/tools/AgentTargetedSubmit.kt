package dev.gvart.genesara.api.internal.mcp.tools

import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.WorldCommandGateway
import dev.gvart.genesara.world.commands.WorldCommand

/**
 * Parse a single agent-target argument (bare UUID or wire-prefixed `agent:<uuid>`), build the
 * command via [build] (the caller closes over the acting agent), and queue it — the shared body
 * of every single-agent-target clan/faction verb (kick / promote / demote / …). Returns a
 * `bad_target_id` rejection without queuing when the target can't be parsed.
 */
internal fun WorldCommandGateway.submitAgentTargeted(
    rawTarget: String,
    engine: TickClock,
    build: (target: AgentId) -> WorldCommand,
): CommandAckResponse {
    val targetId = PrefixedIds.parseAgentLenient(rawTarget)
        ?: return CommandAckResponse.rejected(
            target = rawTarget,
            reason = "bad_target_id",
            detail = "target must be a UUID or agent:<uuid>",
        )
    return submitQueued(build(targetId), engine, target = PrefixedIds.encodeAgent(targetId))
}
