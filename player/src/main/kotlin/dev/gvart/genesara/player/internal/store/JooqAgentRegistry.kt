package dev.gvart.genesara.player.internal.store

import dev.gvart.genesara.account.PlayerId
import dev.gvart.genesara.player.Agent
import dev.gvart.genesara.player.AgentAttributes
import dev.gvart.genesara.player.AgentClass
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentProfile
import dev.gvart.genesara.player.AgentProfileRepository
import dev.gvart.genesara.player.AgentRegistrar
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.player.AttributeDerivation
import dev.gvart.genesara.player.RaceId
import dev.gvart.genesara.player.internal.jooq.tables.records.AgentsRecord
import dev.gvart.genesara.player.internal.jooq.tables.references.AGENTS
import dev.gvart.genesara.player.internal.race.RaceAssigner
import org.jooq.DSLContext
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Component
internal class JooqAgentRegistry(
    private val dsl: DSLContext,
    private val profiles: AgentProfileRepository,
    private val raceAssigner: RaceAssigner,
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
        val race = raceAssigner.assign()
        val attrs = AgentAttributes.DEFAULT + race.attributeMods
        val pools = AttributeDerivation.deriveMaxPools(attrs)

        dsl.insertInto(AGENTS)
            .set(AGENTS.ID, id.id)
            .set(AGENTS.OWNER_ID, owner.id)
            .set(AGENTS.NAME, name)
            .set(AGENTS.API_TOKEN, token)
            .set(AGENTS.RACE_ID, race.id.value)
            .set(AGENTS.LEVEL, INITIAL_LEVEL)
            .set(AGENTS.XP_CURRENT, INITIAL_XP_CURRENT)
            .set(AGENTS.XP_TO_NEXT, INITIAL_XP_TO_NEXT)
            .set(AGENTS.UNSPENT_ATTRIBUTE_POINTS, INITIAL_UNSPENT_POINTS)
            .set(AGENTS.STRENGTH, attrs.strength)
            .set(AGENTS.DEXTERITY, attrs.dexterity)
            .set(AGENTS.CONSTITUTION, attrs.constitution)
            .set(AGENTS.PERCEPTION, attrs.perception)
            .set(AGENTS.INTELLIGENCE, attrs.intelligence)
            .set(AGENTS.LUCK, attrs.luck)
            .execute()
        profiles.save(
            AgentProfile(
                id = id,
                maxHp = pools.maxHp,
                maxStamina = pools.maxStamina,
                maxMana = pools.maxMana,
            )
        )
        return Agent(
            id = id,
            owner = owner,
            name = name,
            apiToken = token,
            classId = null,
            race = race.id,
            level = INITIAL_LEVEL,
            xpCurrent = INITIAL_XP_CURRENT,
            xpToNext = INITIAL_XP_TO_NEXT,
            unspentAttributePoints = INITIAL_UNSPENT_POINTS,
            attributes = attrs,
        )
    }

    private fun AgentsRecord.toAgent(): Agent = Agent(
        id = AgentId(this[AGENTS.ID]!!),
        owner = PlayerId(this[AGENTS.OWNER_ID]!!),
        name = this[AGENTS.NAME]!!,
        apiToken = this[AGENTS.API_TOKEN]!!,
        classId = this[AGENTS.CLASS_ID]?.let(AgentClass::valueOf),
        race = RaceId(this[AGENTS.RACE_ID]!!),
        level = this[AGENTS.LEVEL]!!,
        xpCurrent = this[AGENTS.XP_CURRENT]!!,
        xpToNext = this[AGENTS.XP_TO_NEXT]!!,
        unspentAttributePoints = this[AGENTS.UNSPENT_ATTRIBUTE_POINTS]!!,
        attributes = AgentAttributes(
            strength = this[AGENTS.STRENGTH]!!,
            dexterity = this[AGENTS.DEXTERITY]!!,
            constitution = this[AGENTS.CONSTITUTION]!!,
            perception = this[AGENTS.PERCEPTION]!!,
            intelligence = this[AGENTS.INTELLIGENCE]!!,
            luck = this[AGENTS.LUCK]!!,
        ),
    )

    private companion object {
        const val INITIAL_LEVEL = 1
        const val INITIAL_XP_CURRENT = 0
        const val INITIAL_XP_TO_NEXT = 100
        const val INITIAL_UNSPENT_POINTS = 5
    }
}
