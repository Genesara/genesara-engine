package dev.gvart.genesara.world

import dev.gvart.genesara.world.commands.WorldCommand

interface WorldCommandGateway {
    /**
     * Queue [command] to land at or after [appliesAtTick]. Returns the actual
     * tick the command is queued for, which may be later than the request when
     * the per-world tick has already advanced past the caller's view (the
     * MCP-tool side computes `appliesAtTick` from a global [dev.gvart.genesara.engine.TickClock]
     * that lags the per-world counter by definition). Tool callers should
     * surface the returned value to the agent — that's the tick at which the
     * resulting event will arrive.
     */
    fun submit(command: WorldCommand, appliesAtTick: Long): Long
}