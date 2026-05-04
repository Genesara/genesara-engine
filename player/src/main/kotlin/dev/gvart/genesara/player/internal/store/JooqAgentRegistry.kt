package dev.gvart.genesara.player.internal.store

import dev.gvart.genesara.account.PlayerId
import dev.gvart.genesara.player.Agent
import dev.gvart.genesara.player.AgentAttributes
import dev.gvart.genesara.player.AgentClass
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentLastActiveStore
import dev.gvart.genesara.player.AgentProfile
import dev.gvart.genesara.player.AgentProfileRepository
import dev.gvart.genesara.player.AgentRegistrar
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.player.AllocateAttributesOutcome
import dev.gvart.genesara.player.Attribute
import dev.gvart.genesara.player.AttributeDerivation
import dev.gvart.genesara.player.AttributeMilestoneCrossing
import dev.gvart.genesara.player.AttributePointLoss
import dev.gvart.genesara.player.DeathPenaltyOutcome
import dev.gvart.genesara.player.RaceId
import dev.gvart.genesara.player.internal.jooq.tables.records.AgentsRecord
import dev.gvart.genesara.player.internal.jooq.tables.references.AGENTS
import dev.gvart.genesara.player.internal.race.RaceAssigner
import org.jooq.DSLContext
import org.jooq.TableField
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

@Component
internal class JooqAgentRegistry(
    private val dsl: DSLContext,
    private val profiles: AgentProfileRepository,
    private val raceAssigner: RaceAssigner,
) : AgentRegistry, AgentRegistrar, AgentLastActiveStore {

    override fun find(id: AgentId): Agent? =
        dsl.selectFrom(AGENTS)
            .where(AGENTS.ID.eq(id.id))
            .fetchOne()
            ?.toAgent()

    override fun listForOwner(owner: PlayerId): List<Agent> =
        dsl.selectFrom(AGENTS)
            .where(AGENTS.OWNER_ID.eq(owner.id))
            .orderBy(AGENTS.CREATED_AT.asc())
            .fetch { it.toAgent() }

    @Transactional
    override fun delete(agentId: AgentId): Boolean =
        dsl.deleteFrom(AGENTS).where(AGENTS.ID.eq(agentId.id)).execute() > 0

    override fun findLastActive(agentId: AgentId): Instant? =
        dsl.select(AGENTS.LAST_ACTIVE_AT)
            .from(AGENTS)
            .where(AGENTS.ID.eq(agentId.id))
            .fetchOne()
            ?.get(AGENTS.LAST_ACTIVE_AT)
            ?.toInstant()

    override fun findLastActiveBatch(ids: Collection<AgentId>): Map<AgentId, Instant> {
        if (ids.isEmpty()) return emptyMap()
        return dsl.select(AGENTS.ID, AGENTS.LAST_ACTIVE_AT)
            .from(AGENTS)
            .where(AGENTS.ID.`in`(ids.map { it.id }))
            .and(AGENTS.LAST_ACTIVE_AT.isNotNull)
            .fetch()
            .associate { AgentId(it[AGENTS.ID]!!) to it[AGENTS.LAST_ACTIVE_AT]!!.toInstant() }
    }

    @Transactional
    override fun saveLastActive(updates: Map<AgentId, Instant>) {
        if (updates.isEmpty()) return
        val batch = updates.map { (id, instant) ->
            dsl.update(AGENTS)
                .set(AGENTS.LAST_ACTIVE_AT, instant.atOffset(ZoneOffset.UTC))
                .where(AGENTS.ID.eq(id.id))
        }
        dsl.batch(batch).execute()
    }

    @Transactional
    override fun register(owner: PlayerId, name: String): Agent {
        val id = AgentId(UUID.randomUUID())
        val race = raceAssigner.assign()
        val attrs = AgentAttributes.DEFAULT + race.attributeMods
        val pools = AttributeDerivation.deriveMaxPools(attrs)

        dsl.insertInto(AGENTS)
            .set(AGENTS.ID, id.id)
            .set(AGENTS.OWNER_ID, owner.id)
            .set(AGENTS.NAME, name)
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
            classId = null,
            race = race.id,
            level = INITIAL_LEVEL,
            xpCurrent = INITIAL_XP_CURRENT,
            xpToNext = INITIAL_XP_TO_NEXT,
            unspentAttributePoints = INITIAL_UNSPENT_POINTS,
            attributes = attrs,
        )
    }

    @Transactional
    override fun allocateAttributes(
        agentId: AgentId,
        deltas: Map<Attribute, Int>,
    ): AllocateAttributesOutcome? {
        if (deltas.values.any { it < 0 }) return AllocateAttributesOutcome.NegativeDelta

        val record = lockAgentRow(agentId) ?: return null
        val unspent = record[AGENTS.UNSPENT_ATTRIBUTE_POINTS]!!
        // Sum in Long so a malicious caller can't smuggle past the unspent guard with two
        // big positive ints that wrap around to a negative Int sum.
        val requestedLong = deltas.values.sumOf { it.toLong() }
        if (requestedLong > unspent.toLong()) {
            return AllocateAttributesOutcome.InsufficientPoints(unspent = unspent, requested = requestedLong)
        }
        val requested = requestedLong.toInt()
        if (requested == 0) {
            return AllocateAttributesOutcome.Allocated(
                attributes = record.toAttributes(),
                remainingUnspent = unspent,
                crossedMilestones = emptyList(),
            )
        }

        val oldAttrs = record.toAttributes()
        val newAttrs = oldAttrs.applyDeltas(deltas)
        val crossings = detectMilestoneCrossings(oldAttrs, newAttrs, deltas)

        val update = dsl.update(AGENTS).set(AGENTS.UNSPENT_ATTRIBUTE_POINTS, unspent - requested)
        deltas.forEach { (attr, delta) ->
            if (delta > 0) update.set(columnFor(attr), attr.valueOn(newAttrs))
        }
        update.where(AGENTS.ID.eq(agentId.id)).execute()

        val pools = AttributeDerivation.deriveMaxPools(newAttrs)
        profiles.save(
            AgentProfile(
                id = agentId,
                maxHp = pools.maxHp,
                maxStamina = pools.maxStamina,
                maxMana = pools.maxMana,
            )
        )

        return AllocateAttributesOutcome.Allocated(
            attributes = newAttrs,
            remainingUnspent = unspent - requested,
            crossedMilestones = crossings,
        )
    }

    private fun detectMilestoneCrossings(
        old: AgentAttributes,
        new: AgentAttributes,
        deltas: Map<Attribute, Int>,
    ): List<AttributeMilestoneCrossing> = deltas.entries
        .filter { (_, delta) -> delta > 0 }
        .flatMap { (attr, _) ->
            val oldVal = attr.valueOn(old)
            val newVal = attr.valueOn(new)
            ATTRIBUTE_MILESTONES
                .filter { it in (oldVal + 1)..newVal }
                .map { AttributeMilestoneCrossing(attr, it) }
        }

    private fun AgentAttributes.applyDeltas(deltas: Map<Attribute, Int>): AgentAttributes = AgentAttributes(
        strength = strength + (deltas[Attribute.STRENGTH] ?: 0),
        dexterity = dexterity + (deltas[Attribute.DEXTERITY] ?: 0),
        constitution = constitution + (deltas[Attribute.CONSTITUTION] ?: 0),
        perception = perception + (deltas[Attribute.PERCEPTION] ?: 0),
        intelligence = intelligence + (deltas[Attribute.INTELLIGENCE] ?: 0),
        luck = luck + (deltas[Attribute.LUCK] ?: 0),
    )

    private fun AgentsRecord.toAttributes(): AgentAttributes = AgentAttributes(
        strength = this[AGENTS.STRENGTH]!!,
        dexterity = this[AGENTS.DEXTERITY]!!,
        constitution = this[AGENTS.CONSTITUTION]!!,
        perception = this[AGENTS.PERCEPTION]!!,
        intelligence = this[AGENTS.INTELLIGENCE]!!,
        luck = this[AGENTS.LUCK]!!,
    )

    @Transactional
    override fun applyDeathPenalty(agentId: AgentId, xpLossOnDeath: Int): DeathPenaltyOutcome? {
        require(xpLossOnDeath >= 0) { "xpLossOnDeath must be non-negative, got $xpLossOnDeath" }
        val record = lockAgentRow(agentId) ?: return null

        val xpCurrent = record[AGENTS.XP_CURRENT]!!
        return if (xpCurrent > 0) {
            applyPartialBarPenalty(agentId, xpCurrent, xpLossOnDeath)
        } else {
            applyEmptyBarPenalty(agentId, record)
        }
    }

    /**
     * Row-level lock: serializes concurrent death applications for the same agent across
     * instances (e.g. starvation tick + Phase-2 combat killing-blow). Within a single
     * instance the tick handler's `@Transactional` already serializes; the lock is the
     * forward-looking guard.
     */
    private fun lockAgentRow(agentId: AgentId): AgentsRecord? =
        dsl.selectFrom(AGENTS)
            .where(AGENTS.ID.eq(agentId.id))
            .forUpdate()
            .fetchOne()

    private fun applyPartialBarPenalty(agentId: AgentId, xpCurrent: Int, xpLossOnDeath: Int): DeathPenaltyOutcome {
        val xpLost = minOf(xpCurrent, xpLossOnDeath)
        dsl.update(AGENTS)
            .set(AGENTS.XP_CURRENT, xpCurrent - xpLost)
            .where(AGENTS.ID.eq(agentId.id))
            .execute()
        return DeathPenaltyOutcome(xpLost = xpLost, deleveled = false, attributePointLost = null)
    }

    /**
     * Empty-bar branch: clamp level to 1 and burn 1 attribute point. Prefer the unspent
     * pool, fall back to decrementing the highest allocated attribute. Level-1 agents
     * stay at level 1 — `coerceAtLeast(1)` enforces the floor; the `deleveled` flag
     * reports honestly. If every allocated attribute is already at the [AgentAttributes.MIN_ATTRIBUTE]
     * floor the stat decrement becomes a no-op (we still de-level).
     */
    private fun applyEmptyBarPenalty(agentId: AgentId, record: AgentsRecord): DeathPenaltyOutcome {
        val level = record[AGENTS.LEVEL]!!
        val unspent = record[AGENTS.UNSPENT_ATTRIBUTE_POINTS]!!
        val didDelevel = level > 1
        val newLevel = (level - 1).coerceAtLeast(1)
        val newXpToNext = newLevel * XP_PER_LEVEL

        val update = dsl.update(AGENTS)
            .set(AGENTS.LEVEL, newLevel)
            .set(AGENTS.XP_TO_NEXT, newXpToNext)

        if (unspent > 0) {
            update.set(AGENTS.UNSPENT_ATTRIBUTE_POINTS, unspent - 1)
                .where(AGENTS.ID.eq(agentId.id))
                .execute()
            return DeathPenaltyOutcome(xpLost = 0, deleveled = didDelevel, attributePointLost = AttributePointLoss.Unspent)
        }

        val attrs = AgentAttributes(
            strength = record[AGENTS.STRENGTH]!!,
            dexterity = record[AGENTS.DEXTERITY]!!,
            constitution = record[AGENTS.CONSTITUTION]!!,
            perception = record[AGENTS.PERCEPTION]!!,
            intelligence = record[AGENTS.INTELLIGENCE]!!,
            luck = record[AGENTS.LUCK]!!,
        )
        val target = pickHighestAttribute(attrs)
        val current = target.valueOn(attrs)
        if (current <= AgentAttributes.MIN_ATTRIBUTE) {
            update.where(AGENTS.ID.eq(agentId.id)).execute()
            return DeathPenaltyOutcome(xpLost = 0, deleveled = didDelevel, attributePointLost = null)
        }
        update.set(columnFor(target), current - 1)
            .where(AGENTS.ID.eq(agentId.id))
            .execute()
        return DeathPenaltyOutcome(xpLost = 0, deleveled = didDelevel, attributePointLost = AttributePointLoss.Allocated(target))
    }

    /**
     * Ties broken by [Attribute.ordinal] so the outcome is deterministic regardless of
     * which physical column is tied — the test pins this rule.
     */
    private fun pickHighestAttribute(attrs: AgentAttributes): Attribute =
        Attribute.entries
            .map { it to it.valueOn(attrs) }
            .maxBy { it.second }
            .first

    private fun columnFor(attribute: Attribute): TableField<AgentsRecord, Int?> = when (attribute) {
        Attribute.STRENGTH -> AGENTS.STRENGTH
        Attribute.DEXTERITY -> AGENTS.DEXTERITY
        Attribute.CONSTITUTION -> AGENTS.CONSTITUTION
        Attribute.PERCEPTION -> AGENTS.PERCEPTION
        Attribute.INTELLIGENCE -> AGENTS.INTELLIGENCE
        Attribute.LUCK -> AGENTS.LUCK
    }

    private fun AgentsRecord.toAgent(): Agent = Agent(
        id = AgentId(this[AGENTS.ID]!!),
        owner = PlayerId(this[AGENTS.OWNER_ID]!!),
        name = this[AGENTS.NAME]!!,
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
        const val XP_PER_LEVEL = 100
        val ATTRIBUTE_MILESTONES = listOf(50, 100, 200)
    }
}
