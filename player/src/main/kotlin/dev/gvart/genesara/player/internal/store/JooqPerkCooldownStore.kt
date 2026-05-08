package dev.gvart.genesara.player.internal.store

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.PerkCooldownStore
import dev.gvart.genesara.player.PerkId
import dev.gvart.genesara.player.internal.jooq.tables.references.AGENT_PERK_COOLDOWNS
import org.jooq.DSLContext
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
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
    override fun arm(agent: AgentId, perk: PerkId, untilTick: Long) {
        dsl.insertInto(AGENT_PERK_COOLDOWNS)
            .set(AGENT_PERK_COOLDOWNS.AGENT_ID, agent.id)
            .set(AGENT_PERK_COOLDOWNS.PERK_ID, perk.value)
            .set(AGENT_PERK_COOLDOWNS.COOLDOWN_UNTIL_TICK, untilTick)
            .onConflict(AGENT_PERK_COOLDOWNS.AGENT_ID, AGENT_PERK_COOLDOWNS.PERK_ID)
            .doUpdate()
            .set(AGENT_PERK_COOLDOWNS.COOLDOWN_UNTIL_TICK, untilTick)
            .execute()
    }
}
