package dev.gvart.genesara.player.internal.store

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.PerkCooldownStore
import dev.gvart.genesara.player.PerkId
import dev.gvart.genesara.player.internal.jooq.tables.references.AGENT_PERK_COOLDOWNS
import org.jooq.DSLContext
import org.springframework.transaction.annotation.Transactional

/**
 * Postgres-backed [PerkCooldownStore]. Retained off the Spring component graph
 * after the shard-readiness Step 2 swap (see `docs/shard-readiness-sequence.md`)
 * to back the future `T_persist` write-through for cooldowns longer than the
 * Redis-only window. The active runtime store is [RedisPerkCooldownStore].
 */
internal class JooqPerkCooldownStore(
    private val dsl: DSLContext,
) : PerkCooldownStore {

    @Transactional(readOnly = true)
    override fun isReady(agent: AgentId, perk: PerkId, tick: Long): Boolean {
        val until = readyAtTick(agent, perk) ?: return true
        return tick >= until
    }

    @Transactional(readOnly = true)
    override fun readyAtTick(agent: AgentId, perk: PerkId): Long? =
        dsl.select(AGENT_PERK_COOLDOWNS.COOLDOWN_UNTIL_TICK)
            .from(AGENT_PERK_COOLDOWNS)
            .where(AGENT_PERK_COOLDOWNS.AGENT_ID.eq(agent.id))
            .and(AGENT_PERK_COOLDOWNS.PERK_ID.eq(perk.value))
            .fetchOne(AGENT_PERK_COOLDOWNS.COOLDOWN_UNTIL_TICK)

    @Transactional
    override fun arm(agent: AgentId, perk: PerkId, untilTick: Long, currentTick: Long) {
        dsl.insertInto(AGENT_PERK_COOLDOWNS)
            .set(AGENT_PERK_COOLDOWNS.AGENT_ID, agent.id)
            .set(AGENT_PERK_COOLDOWNS.PERK_ID, perk.value)
            .set(AGENT_PERK_COOLDOWNS.COOLDOWN_UNTIL_TICK, untilTick)
            .onConflict(AGENT_PERK_COOLDOWNS.AGENT_ID, AGENT_PERK_COOLDOWNS.PERK_ID)
            .doUpdate()
            .set(AGENT_PERK_COOLDOWNS.COOLDOWN_UNTIL_TICK, untilTick)
            .execute()
    }

    @Transactional(readOnly = true)
    override fun byAgents(agents: Set<AgentId>): Map<AgentId, Map<PerkId, Long>> {
        if (agents.isEmpty()) return emptyMap()
        return dsl.select(
            AGENT_PERK_COOLDOWNS.AGENT_ID,
            AGENT_PERK_COOLDOWNS.PERK_ID,
            AGENT_PERK_COOLDOWNS.COOLDOWN_UNTIL_TICK,
        )
            .from(AGENT_PERK_COOLDOWNS)
            .where(AGENT_PERK_COOLDOWNS.AGENT_ID.`in`(agents.map { it.id }))
            .fetch()
            .groupBy(
                { AgentId(it[AGENT_PERK_COOLDOWNS.AGENT_ID]!!) },
                { PerkId(it[AGENT_PERK_COOLDOWNS.PERK_ID]!!) to it[AGENT_PERK_COOLDOWNS.COOLDOWN_UNTIL_TICK]!! },
            )
            .mapValues { (_, pairs) -> pairs.toMap() }
    }
}
