package dev.gvart.genesara.world.internal.behavior

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.internal.jooq.tables.references.AGENT_ACTION_COUNTERS
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class JooqBehaviorTracker(
    private val dsl: DSLContext,
) : BehaviorTracker {

    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    override fun record(agent: AgentId, category: ActionCategory, tick: Long) {
        dsl.insertInto(AGENT_ACTION_COUNTERS)
            .set(AGENT_ACTION_COUNTERS.AGENT_ID, agent.id)
            .set(AGENT_ACTION_COUNTERS.CATEGORY, category.name)
            .set(AGENT_ACTION_COUNTERS.ACTION_COUNT, 1)
            .set(AGENT_ACTION_COUNTERS.LAST_INCREMENTED_AT_TICK, tick)
            .onConflict(AGENT_ACTION_COUNTERS.AGENT_ID, AGENT_ACTION_COUNTERS.CATEGORY)
            .doUpdate()
            .set(AGENT_ACTION_COUNTERS.ACTION_COUNT, AGENT_ACTION_COUNTERS.ACTION_COUNT.plus(1))
            .set(AGENT_ACTION_COUNTERS.LAST_INCREMENTED_AT_TICK, tick)
            .execute()
    }

    @Transactional(readOnly = true)
    override fun snapshotFor(agent: AgentId): Map<ActionCategory, Int> =
        readSnapshot(agent) { actionCount, _ -> actionCount }

    @Transactional
    override fun markBaseline(agent: AgentId) {
        dsl.update(AGENT_ACTION_COUNTERS)
            .set(AGENT_ACTION_COUNTERS.BASELINE_COUNT, AGENT_ACTION_COUNTERS.ACTION_COUNT)
            .where(AGENT_ACTION_COUNTERS.AGENT_ID.eq(agent.id))
            .execute()
    }

    @Transactional(readOnly = true)
    override fun snapshotForWindow(agent: AgentId): Map<ActionCategory, Int> =
        readSnapshot(agent) { actionCount, baseline -> actionCount - baseline }

    private fun readSnapshot(
        agent: AgentId,
        project: (actionCount: Int, baseline: Int) -> Int,
    ): Map<ActionCategory, Int> =
        dsl.select(
            AGENT_ACTION_COUNTERS.CATEGORY,
            AGENT_ACTION_COUNTERS.ACTION_COUNT,
            AGENT_ACTION_COUNTERS.BASELINE_COUNT,
        )
            .from(AGENT_ACTION_COUNTERS)
            .where(AGENT_ACTION_COUNTERS.AGENT_ID.eq(agent.id))
            .fetch()
            .mapNotNull { row ->
                val raw = row[AGENT_ACTION_COUNTERS.CATEGORY] ?: return@mapNotNull null
                val category = runCatching { ActionCategory.valueOf(raw) }.getOrNull() ?: run {
                    // Drop rather than throw: an old pod surviving past a
                    // deploy that adds a category must keep scoring against
                    // the categories it knows. Surfaces as a warn so the gap
                    // doesn't go unnoticed forever.
                    log.warn("agent_action_counters row for {} has unknown category '{}'; dropping from snapshot", agent.id, raw)
                    return@mapNotNull null
                }
                val count = row[AGENT_ACTION_COUNTERS.ACTION_COUNT] ?: 0
                val baseline = row[AGENT_ACTION_COUNTERS.BASELINE_COUNT] ?: 0
                val projected = project(count, baseline)
                if (projected <= 0) null else category to projected
            }
            .toMap()
}
