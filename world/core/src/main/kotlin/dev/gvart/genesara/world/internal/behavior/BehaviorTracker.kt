package dev.gvart.genesara.world.internal.behavior

import dev.gvart.genesara.player.AgentId

/**
 * Silent per-agent action counters that feed the level-10 class-choice
 * fingerprint (#33) and, later, the L50 evolution scoring (#34).
 *
 * Contract — counters are **never** exposed to agents: no MCP tool, projection,
 * or `get_status` field surfaces them. The interface stays `internal` to the
 * world module so an accidental import from `:api` fails to compile.
 */
interface BehaviorTracker {

    fun record(agent: AgentId, category: ActionCategory, tick: Long)

    /** Cumulative count per category; categories the agent has never touched are absent. */
    fun snapshotFor(agent: AgentId): Map<ActionCategory, Int>

    /**
     * Snapshot the current cumulative counters as the per-category baseline for
     * windowed reads. Called from the `ClassChosen` listener so the L50 evolution
     * scorer (#34) sees only the agent's post-L10 behaviour. Idempotent and safe
     * to re-run; the DB-side CHECK clamps `baseline_count <= action_count`.
     *
     * Categories the agent first touches after this call implicitly land with
     * `baseline_count = 0`, so `snapshotForWindow` returns the full count for
     * those — exactly the right answer.
     */
    fun markBaseline(agent: AgentId)

    /**
     * Per-category counts since the last [markBaseline] (i.e. since the agent
     * picked their L10 class). Returns an empty map when the agent has no rows
     * yet. Categories with `action_count == baseline_count` are dropped.
     */
    fun snapshotForWindow(agent: AgentId): Map<ActionCategory, Int>
}
