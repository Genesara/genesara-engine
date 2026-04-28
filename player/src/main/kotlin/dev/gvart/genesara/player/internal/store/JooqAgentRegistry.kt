package dev.gvart.genesara.player.internal.store

import dev.gvart.genesara.account.PlayerId
import dev.gvart.genesara.player.Agent
import dev.gvart.genesara.player.AgentClass
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentProfile
import dev.gvart.genesara.player.AgentProfileRepository
import dev.gvart.genesara.player.AgentRegistrar
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.player.internal.jooq.tables.records.AgentsRecord
import dev.gvart.genesara.player.internal.jooq.tables.references.AGENTS
import org.jooq.DSLContext
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Component
internal class JooqAgentRegistry(
    private val dsl: DSLContext,
    private val profiles: AgentProfileRepository,
) : AgentRegistry, AgentRegistrar {

    override fun find(id: AgentId): Agent? =
        dsl.selectFrom(AGENTS)
            .where(AGENTS.ID.eq(id.id))
            .fetchOne()
            ?.toAgent()

    override fun findByToken(token: String): Agent? =
        dsl.selectFrom(AGENTS)
            .where(AGENTS.API_TOKEN.eq(token))
            .fetchOne()
            ?.toAgent()

    override fun listForOwner(owner: PlayerId): List<Agent> =
        dsl.selectFrom(AGENTS)
            .where(AGENTS.OWNER_ID.eq(owner.id))
            .orderBy(AGENTS.CREATED_AT.asc())
            .fetch { it.toAgent() }

    @Transactional
    override fun register(owner: PlayerId, name: String): Agent {
        val id = AgentId(UUID.randomUUID())
        val token = UUID.randomUUID().toString().replace("-", "")
        dsl.insertInto(AGENTS)
            .set(AGENTS.ID, id.id)
            .set(AGENTS.OWNER_ID, owner.id)
            .set(AGENTS.NAME, name)
            .set(AGENTS.API_TOKEN, token)
            .execute()
        profiles.save(AgentProfile(id = id, maxHp = DEFAULT_MAX_HP, maxStamina = DEFAULT_MAX_STAMINA, maxMana = DEFAULT_MAX_MANA))
        return Agent(id = id, owner = owner, name = name, apiToken = token, classId = null)
    }

    private fun AgentsRecord.toAgent(): Agent = Agent(
        id = AgentId(this[AGENTS.ID]!!),
        owner = PlayerId(this[AGENTS.OWNER_ID]!!),
        name = this[AGENTS.NAME]!!,
        apiToken = this[AGENTS.API_TOKEN]!!,
        classId = this[AGENTS.CLASS_ID]?.let(AgentClass::valueOf),
    )

    private companion object {
        const val DEFAULT_MAX_HP = 100
        const val DEFAULT_MAX_STAMINA = 50
        const val DEFAULT_MAX_MANA = 0
    }
}
