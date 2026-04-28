package dev.gvart.genesara.player.internal.profile

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentProfile
import dev.gvart.genesara.player.AgentProfileLookup
import dev.gvart.genesara.player.AgentProfileRepository
import dev.gvart.genesara.player.internal.jooq.tables.references.AGENT_PROFILES
import org.jooq.DSLContext
import org.springframework.stereotype.Component

@Component
internal class JooqAgentProfileStore(
    private val dsl: DSLContext,
) : AgentProfileLookup, AgentProfileRepository {

    override fun find(id: AgentId): AgentProfile? =
        dsl.select(AGENT_PROFILES.AGENT_ID, AGENT_PROFILES.MAX_HP, AGENT_PROFILES.MAX_STAMINA, AGENT_PROFILES.MAX_MANA)
            .from(AGENT_PROFILES)
            .where(AGENT_PROFILES.AGENT_ID.eq(id.id))
            .fetchOne()
            ?.let {
                AgentProfile(
                    id = AgentId(it[AGENT_PROFILES.AGENT_ID]!!),
                    maxHp = it[AGENT_PROFILES.MAX_HP]!!,
                    maxStamina = it[AGENT_PROFILES.MAX_STAMINA]!!,
                    maxMana = it[AGENT_PROFILES.MAX_MANA]!!,
                )
            }

    override fun save(profile: AgentProfile) {
        dsl.insertInto(AGENT_PROFILES)
            .set(AGENT_PROFILES.AGENT_ID, profile.id.id)
            .set(AGENT_PROFILES.MAX_HP, profile.maxHp)
            .set(AGENT_PROFILES.MAX_STAMINA, profile.maxStamina)
            .set(AGENT_PROFILES.MAX_MANA, profile.maxMana)
            .onConflict(AGENT_PROFILES.AGENT_ID)
            .doUpdate()
            .set(AGENT_PROFILES.MAX_HP, profile.maxHp)
            .set(AGENT_PROFILES.MAX_STAMINA, profile.maxStamina)
            .set(AGENT_PROFILES.MAX_MANA, profile.maxMana)
            .execute()
    }
}
