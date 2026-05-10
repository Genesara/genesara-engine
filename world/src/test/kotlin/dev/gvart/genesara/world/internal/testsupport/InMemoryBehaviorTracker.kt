package dev.gvart.genesara.world.internal.testsupport

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.internal.behavior.ActionCategory
import dev.gvart.genesara.world.internal.behavior.BehaviorTracker

// Single-threaded by design — reducer tests run sequentially. Wrap calls in
// a lock if a future test fans out parallel reducers against a shared instance.
internal class InMemoryBehaviorTracker : BehaviorTracker {
    private val counts = mutableMapOf<Pair<AgentId, ActionCategory>, Int>()
    private val lastTicks = mutableMapOf<Pair<AgentId, ActionCategory>, Long>()

    override fun record(agent: AgentId, category: ActionCategory, tick: Long) {
        val key = agent to category
        counts.merge(key, 1, Int::plus)
        lastTicks[key] = tick
    }

    override fun snapshotFor(agent: AgentId): Map<ActionCategory, Int> =
        counts.entries
            .filter { it.key.first == agent }
            .associate { it.key.second to it.value }

    fun lastTickFor(agent: AgentId, category: ActionCategory): Long? =
        lastTicks[agent to category]
}
