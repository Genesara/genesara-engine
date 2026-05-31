package dev.gvart.genesara.player.internal.store

import dev.gvart.genesara.account.PlayerId
import dev.gvart.genesara.player.Agent
import dev.gvart.genesara.player.AgentAttributes
import dev.gvart.genesara.player.AgentClass
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.FactionRank
import dev.gvart.genesara.player.AgentLastActiveStore
import dev.gvart.genesara.player.AddCharacterXpOutcome
import dev.gvart.genesara.player.AdminAttributeOverrides
import dev.gvart.genesara.player.AgentProfile
import dev.gvart.genesara.player.AgentProfileRepository
import dev.gvart.genesara.player.AgentRegistrar
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.player.AllocateAttributesOutcome
import dev.gvart.genesara.player.AssignClassOutcome
import dev.gvart.genesara.player.AssignEvolutionOutcome
import dev.gvart.genesara.player.Attribute
import dev.gvart.genesara.player.AttributeDerivation
import dev.gvart.genesara.player.AttributeMilestoneCrossing
import dev.gvart.genesara.player.AttributePointLoss
import dev.gvart.genesara.player.ClassLookup
import dev.gvart.genesara.player.ClassOffer
import dev.gvart.genesara.player.DeathPenaltyOutcome
import dev.gvart.genesara.player.MisconductOutcome
import dev.gvart.genesara.player.OutlawState
import dev.gvart.genesara.player.RaceId
import dev.gvart.genesara.player.RecordClassOfferOutcome
import dev.gvart.genesara.player.RecordEvolutionOfferOutcome
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
    private val classes: ClassLookup,
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

    override fun totalCount(): Long =
        dsl.selectCount().from(AGENTS).fetchOne(0, Long::class.java) ?: 0L

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
            val attrs = record.toAttributes()
            return AllocateAttributesOutcome.Allocated(
                attributes = attrs,
                remainingUnspent = unspent,
                crossedMilestones = emptyList(),
                pools = AttributeDerivation.deriveMaxPools(attrs),
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
            pools = pools,
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
    override fun addCharacterXp(agentId: AgentId, delta: Int): AddCharacterXpOutcome? {
        if (delta < 0) return AddCharacterXpOutcome.NegativeDelta
        val record = lockAgentRow(agentId) ?: return null

        val previousLevel = record[AGENTS.LEVEL]!!
        val previousClassId = record[AGENTS.CLASS_ID]?.let(AgentClass::valueOf)
        val previousXpCurrent = record[AGENTS.XP_CURRENT]!!
        val previousXpToNext = record[AGENTS.XP_TO_NEXT]!!
        val previousUnspent = record[AGENTS.UNSPENT_ATTRIBUTE_POINTS]!!

        if (delta == 0) {
            return AddCharacterXpOutcome.Granted(
                previousLevel = previousLevel,
                currentLevel = previousLevel,
                xpCurrent = previousXpCurrent,
                xpToNext = previousXpToNext,
                unspentAttributePoints = previousUnspent,
                accruedDelta = 0,
                cappedAtPendingClassChoice = false,
                cappedAtPendingEvolutionChoice = false,
            )
        }

        val capLevel = computeCapLevel(previousClassId)

        var level = previousLevel
        var xpCurrent = previousXpCurrent + delta
        var xpToNext = previousXpToNext
        var unspent = previousUnspent
        var capped = false
        var droppedSurplus = 0

        while (xpCurrent >= xpToNext) {
            if (level >= capLevel) {
                droppedSurplus = xpCurrent - xpToNext
                xpCurrent = xpToNext
                capped = true
                break
            }
            xpCurrent -= xpToNext
            level += 1
            xpToNext = level * XP_PER_LEVEL
            unspent += UNSPENT_PER_LEVEL_UP
        }

        dsl.update(AGENTS)
            .set(AGENTS.LEVEL, level)
            .set(AGENTS.XP_CURRENT, xpCurrent)
            .set(AGENTS.XP_TO_NEXT, xpToNext)
            .set(AGENTS.UNSPENT_ATTRIBUTE_POINTS, unspent)
            .where(AGENTS.ID.eq(agentId.id))
            .execute()

        return AddCharacterXpOutcome.Granted(
            previousLevel = previousLevel,
            currentLevel = level,
            xpCurrent = xpCurrent,
            xpToNext = xpToNext,
            unspentAttributePoints = unspent,
            accruedDelta = delta - droppedSurplus,
            cappedAtPendingClassChoice = capped && capLevel == LEVEL_TEN_PENDING_CHOICE_CAP,
            cappedAtPendingEvolutionChoice = capped && capLevel == LEVEL_FIFTY_EVOLUTION_CAP,
        )
    }

    /**
     * Decides which level boundary halts XP cascade for this agent:
     *  - **Pre-L10** (no class): cap at level 10 — the agent must call `select_class`.
     *  - **On a base class** (catalog `parentClass == null`) with declared
     *    evolutions: cap at level 50 — the agent must call `select_evolution`.
     *    The evolutions check defends against a YAML edit that empties a base
     *    class's evolution list; without a candidate to offer the cap would
     *    strand the agent at L50 forever.
     *  - **On an evolution class**: no cap. L100 stays deferred per `mechanics-reference.md` §4.1.
     */
    private fun computeCapLevel(previousClassId: AgentClass?): Int {
        if (previousClassId == null) return LEVEL_TEN_PENDING_CHOICE_CAP
        val def = classes.byId(previousClassId) ?: return Int.MAX_VALUE
        return if (def.parentClass == null && def.evolutions.size >= 2) {
            LEVEL_FIFTY_EVOLUTION_CAP
        } else {
            Int.MAX_VALUE
        }
    }

    @Transactional
    override fun recordPendingClassChoice(agentId: AgentId, offer: ClassOffer): RecordClassOfferOutcome {
        val record = lockAgentRow(agentId) ?: return RecordClassOfferOutcome.UnknownAgent
        if (record[AGENTS.CLASS_ID] != null) return RecordClassOfferOutcome.AlreadyClassed
        val existing = record.toClassOfferOrNull()
        if (existing != null) return RecordClassOfferOutcome.AlreadyOffered(existing)

        dsl.update(AGENTS)
            .set(AGENTS.OFFERED_CLASS_A, offer.first.name)
            .set(AGENTS.OFFERED_CLASS_B, offer.second.name)
            .where(AGENTS.ID.eq(agentId.id))
            .execute()
        return RecordClassOfferOutcome.Recorded
    }

    @Transactional
    override fun assignClass(agentId: AgentId, classId: AgentClass): AssignClassOutcome {
        val record = lockAgentRow(agentId) ?: return AssignClassOutcome.UnknownAgent
        record[AGENTS.CLASS_ID]?.let { existing ->
            return AssignClassOutcome.AlreadyClassed(AgentClass.valueOf(existing))
        }
        val pending = record.toClassOfferOrNull() ?: return AssignClassOutcome.NoPendingOffer
        if (!pending.contains(classId)) return AssignClassOutcome.NotInOffer(pending)

        dsl.update(AGENTS)
            .set(AGENTS.CLASS_ID, classId.name)
            .setNull(AGENTS.OFFERED_CLASS_A)
            .setNull(AGENTS.OFFERED_CLASS_B)
            .where(AGENTS.ID.eq(agentId.id))
            .execute()
        return AssignClassOutcome.Assigned
    }

    private fun AgentsRecord.toClassOfferOrNull(): ClassOffer? {
        val a = this[AGENTS.OFFERED_CLASS_A]?.let(AgentClass::valueOf) ?: return null
        val b = this[AGENTS.OFFERED_CLASS_B]?.let(AgentClass::valueOf) ?: return null
        // V207 enforces a != b at the DB layer; this guard keeps reads alive on a
        // corrupt row instead of throwing through every find()/get_status call.
        if (a == b) return null
        return ClassOffer(a, b)
    }

    @Transactional
    override fun recordPendingEvolutionChoice(agentId: AgentId, offer: ClassOffer): RecordEvolutionOfferOutcome {
        val record = lockAgentRow(agentId) ?: return RecordEvolutionOfferOutcome.UnknownAgent
        val classId = record[AGENTS.CLASS_ID]?.let(AgentClass::valueOf)
            ?: return RecordEvolutionOfferOutcome.NoClassAssigned
        val def = classes.byId(classId)
        if (def?.parentClass != null) {
            return RecordEvolutionOfferOutcome.AlreadyEvolved(classId)
        }
        for (candidate in offer.toList()) {
            val candidateDef = classes.byId(candidate)
            if (candidateDef?.parentClass != classId) {
                return RecordEvolutionOfferOutcome.InvalidCandidate(candidate, classId)
            }
        }
        val existing = record.toEvolutionOfferOrNull()
        if (existing != null) return RecordEvolutionOfferOutcome.AlreadyOffered(existing)

        dsl.update(AGENTS)
            .set(AGENTS.OFFERED_EVOLUTION_A, offer.first.name)
            .set(AGENTS.OFFERED_EVOLUTION_B, offer.second.name)
            .where(AGENTS.ID.eq(agentId.id))
            .execute()
        return RecordEvolutionOfferOutcome.Recorded
    }

    @Transactional
    override fun assignEvolution(agentId: AgentId, evolutionId: AgentClass): AssignEvolutionOutcome {
        val record = lockAgentRow(agentId) ?: return AssignEvolutionOutcome.UnknownAgent
        val classId = record[AGENTS.CLASS_ID]?.let(AgentClass::valueOf)
            ?: return AssignEvolutionOutcome.NoClassAssigned
        val def = classes.byId(classId)
        if (def?.parentClass != null) {
            return AssignEvolutionOutcome.AlreadyEvolved(classId)
        }
        val pending = record.toEvolutionOfferOrNull() ?: return AssignEvolutionOutcome.NoPendingOffer
        if (!pending.contains(evolutionId)) return AssignEvolutionOutcome.NotInOffer(pending)
        // Spec mechanics-reference §4.1: select_evolution validates BOTH offer
        // membership AND that the chosen class's parentClass equals the agent's
        // current class. The membership check above is a transitive guarantee
        // (the offer was scored from evolutionsOf(parent)), but a corrupt row
        // or a future caller bypassing the emitter could violate it; the
        // catalog re-check is the load-bearing guard.
        val targetParent = classes.byId(evolutionId)?.parentClass
        if (targetParent != classId) {
            return AssignEvolutionOutcome.WrongParent(expectedParent = classId, actualParent = targetParent)
        }

        dsl.update(AGENTS)
            .set(AGENTS.CLASS_ID, evolutionId.name)
            .setNull(AGENTS.OFFERED_EVOLUTION_A)
            .setNull(AGENTS.OFFERED_EVOLUTION_B)
            .where(AGENTS.ID.eq(agentId.id))
            .execute()
        // TODO(post-#34, equipment-revalidation): when a future evolution introduces
        // a forbidden-combat-skill the parent doesn't have (today only the
        // RESEARCHER family forbids FIREARMS, and ClassValidator enforces parent
        // forbid inheritance — so no L10→L50 transition can break an existing equip),
        // wire an EventListener<ClassEvolved> in :world that auto-unequips the
        // affected items and emits a CLASS_FORBIDDEN_BY_EVOLUTION rejection so the
        // agent finds out about it.
        return AssignEvolutionOutcome.Assigned(from = classId, to = evolutionId)
    }

    private fun AgentsRecord.toEvolutionOfferOrNull(): ClassOffer? {
        val a = this[AGENTS.OFFERED_EVOLUTION_A]?.let(AgentClass::valueOf) ?: return null
        val b = this[AGENTS.OFFERED_EVOLUTION_B]?.let(AgentClass::valueOf) ?: return null
        if (a == b) return null
        return ClassOffer(a, b)
    }

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
     * floor the stat decrement becomes a no-op and the outcome reports
     * [AttributePointLoss.NoLossAtFloor] (the de-level itself still applies when level > 1).
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
            return DeathPenaltyOutcome(
                xpLost = 0,
                deleveled = didDelevel,
                attributePointLost = AttributePointLoss.NoLossAtFloor,
            )
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

    @Transactional
    override fun adminSetLevel(agentId: AgentId, level: Int): Agent? {
        require(level >= INITIAL_LEVEL) { "level must be >= $INITIAL_LEVEL, got $level" }
        val record = lockAgentRow(agentId) ?: return null
        val newXpToNext = level * XP_PER_LEVEL
        val newXpCurrent = record[AGENTS.XP_CURRENT]!!.coerceAtMost(newXpToNext)
        dsl.update(AGENTS)
            .set(AGENTS.LEVEL, level)
            .set(AGENTS.XP_TO_NEXT, newXpToNext)
            .set(AGENTS.XP_CURRENT, newXpCurrent)
            .where(AGENTS.ID.eq(agentId.id))
            .execute()
        return find(agentId)
    }

    @Transactional
    override fun adminSetAttributes(agentId: AgentId, set: AdminAttributeOverrides): Agent? {
        validateOverrides(set)
        val record = lockAgentRow(agentId) ?: return null
        val finalAttrs = AgentAttributes(
            strength = set.strength ?: record[AGENTS.STRENGTH]!!,
            dexterity = set.dexterity ?: record[AGENTS.DEXTERITY]!!,
            constitution = set.constitution ?: record[AGENTS.CONSTITUTION]!!,
            perception = set.perception ?: record[AGENTS.PERCEPTION]!!,
            intelligence = set.intelligence ?: record[AGENTS.INTELLIGENCE]!!,
            luck = set.luck ?: record[AGENTS.LUCK]!!,
        )
        val finalUnspent = set.unspent ?: record[AGENTS.UNSPENT_ATTRIBUTE_POINTS]!!
        dsl.update(AGENTS)
            .set(AGENTS.STRENGTH, finalAttrs.strength)
            .set(AGENTS.DEXTERITY, finalAttrs.dexterity)
            .set(AGENTS.CONSTITUTION, finalAttrs.constitution)
            .set(AGENTS.PERCEPTION, finalAttrs.perception)
            .set(AGENTS.INTELLIGENCE, finalAttrs.intelligence)
            .set(AGENTS.LUCK, finalAttrs.luck)
            .set(AGENTS.UNSPENT_ATTRIBUTE_POINTS, finalUnspent)
            .where(AGENTS.ID.eq(agentId.id))
            .execute()

        val pools = AttributeDerivation.deriveMaxPools(finalAttrs)
        profiles.save(
            AgentProfile(
                id = agentId,
                maxHp = pools.maxHp,
                maxStamina = pools.maxStamina,
                maxMana = pools.maxMana,
            ),
        )
        return find(agentId)
    }

    private fun validateOverrides(set: AdminAttributeOverrides) {
        listOf(
            "strength" to set.strength,
            "dexterity" to set.dexterity,
            "constitution" to set.constitution,
            "perception" to set.perception,
            "intelligence" to set.intelligence,
            "luck" to set.luck,
        ).forEach { (name, value) ->
            if (value != null) {
                require(value >= AgentAttributes.MIN_ATTRIBUTE) {
                    "$name must be >= ${AgentAttributes.MIN_ATTRIBUTE}, got $value"
                }
            }
        }
        set.unspent?.let { require(it >= 0) { "unspent must be >= 0, got $it" } }
    }

    @Transactional
    override fun adminAssignClass(agentId: AgentId, classId: AgentClass): Agent? {
        lockAgentRow(agentId) ?: return null
        dsl.update(AGENTS)
            .set(AGENTS.CLASS_ID, classId.name)
            .setNull(AGENTS.OFFERED_CLASS_A)
            .setNull(AGENTS.OFFERED_CLASS_B)
            .where(AGENTS.ID.eq(agentId.id))
            .execute()
        return find(agentId)
    }

    @Transactional
    override fun adminClearClassAndOffers(agentId: AgentId): Agent? {
        lockAgentRow(agentId) ?: return null
        dsl.update(AGENTS)
            .setNull(AGENTS.CLASS_ID)
            .setNull(AGENTS.OFFERED_CLASS_A)
            .setNull(AGENTS.OFFERED_CLASS_B)
            .setNull(AGENTS.OFFERED_EVOLUTION_A)
            .setNull(AGENTS.OFFERED_EVOLUTION_B)
            .where(AGENTS.ID.eq(agentId.id))
            .execute()
        return find(agentId)
    }

    @Transactional
    override fun adminClearPendingOffers(agentId: AgentId): Agent? {
        lockAgentRow(agentId) ?: return null
        dsl.update(AGENTS)
            .setNull(AGENTS.OFFERED_CLASS_A)
            .setNull(AGENTS.OFFERED_CLASS_B)
            .setNull(AGENTS.OFFERED_EVOLUTION_A)
            .setNull(AGENTS.OFFERED_EVOLUTION_B)
            .where(AGENTS.ID.eq(agentId.id))
            .execute()
        return find(agentId)
    }

    @Transactional
    override fun adjustAuthority(agentId: AgentId, delta: Int): Int? = adjustReputation(agentId, delta, AGENTS.AUTHORITY)

    @Transactional
    override fun adjustFame(agentId: AgentId, delta: Int): Int? = adjustReputation(agentId, delta, AGENTS.FAME)

    @Transactional
    override fun setFactionRank(agentId: AgentId, rank: FactionRank?): Boolean {
        lockAgentRow(agentId) ?: return false
        dsl.update(AGENTS)
            .set(AGENTS.FACTION_RANK, rank?.name)
            .where(AGENTS.ID.eq(agentId.id))
            .execute()
        return true
    }

    @Transactional
    override fun adjustMisconduct(
        agentId: AgentId,
        delta: Int,
        watchedAt: Int,
        outlawAt: Int,
    ): MisconductOutcome? {
        val record = lockAgentRow(agentId) ?: return null
        val oldScore = record[AGENTS.OUTLAW_MISCONDUCT_SCORE]!!
        val oldState = parseOutlawState(record[AGENTS.OUTLAW_STATE]!!)
        // Long-saturate then clamp at zero — the schema's CHECK enforces non-negative,
        // and we want a -1000 pardon delta to land at 0, not blow up the constraint.
        val newScore = (oldScore.toLong() + delta.toLong())
            .coerceIn(0L, Int.MAX_VALUE.toLong())
            .toInt()
        val newState = OutlawState.deriveFrom(newScore, watchedAt, outlawAt)
        dsl.update(AGENTS)
            .set(AGENTS.OUTLAW_MISCONDUCT_SCORE, newScore)
            .set(AGENTS.OUTLAW_STATE, newState.name)
            .where(AGENTS.ID.eq(agentId.id))
            .execute()
        return MisconductOutcome(agentId, oldScore, newScore, oldState, newState)
    }

    @Transactional
    override fun decayMisconductScores(
        amount: Int,
        watchedAt: Int,
        outlawAt: Int,
    ): List<MisconductOutcome> {
        require(amount >= 0) { "decay amount must be non-negative, got $amount" }
        if (amount == 0) return emptyList()
        // Single sweep query reads every misconduct-flagged agent, recomputes the
        // pair locally, then issues per-row updates via jOOQ batch. Per-row writes
        // (not bulk UPDATE) so the state column stays consistent with the score
        // bucket — a bulk `SET state = CASE ... END` would duplicate the
        // bucketing rule from [OutlawState.deriveFrom] in SQL.
        // The partial index `agents_outlaw_misconduct_active_idx` (V210) keeps
        // this scan proportional to the active-offender subset, not the full
        // agent population.
        // TODO(#17-followup): bucket outcomes by (newScore, newState) and emit
        //  one bulk UPDATE per bucket once the flagged population grows past a
        //  few thousand. Per-row batch is fine for the v1 agent count.
        val records = dsl.selectFrom(AGENTS)
            .where(AGENTS.OUTLAW_MISCONDUCT_SCORE.gt(0))
            .fetch()
        if (records.isEmpty()) return emptyList()

        val outcomes = ArrayList<MisconductOutcome>(records.size)
        val updates = records.mapNotNull { record ->
            val agentId = AgentId(record[AGENTS.ID]!!)
            val oldScore = record[AGENTS.OUTLAW_MISCONDUCT_SCORE]!!
            val oldState = parseOutlawState(record[AGENTS.OUTLAW_STATE]!!)
            val newScore = (oldScore - amount).coerceAtLeast(0)
            if (newScore == oldScore) return@mapNotNull null
            val newState = OutlawState.deriveFrom(newScore, watchedAt, outlawAt)
            outcomes += MisconductOutcome(agentId, oldScore, newScore, oldState, newState)
            dsl.update(AGENTS)
                .set(AGENTS.OUTLAW_MISCONDUCT_SCORE, newScore)
                .set(AGENTS.OUTLAW_STATE, newState.name)
                .where(AGENTS.ID.eq(agentId.id))
        }
        if (updates.isNotEmpty()) dsl.batch(updates).execute()
        return outcomes
    }

    /**
     * Defensive parse: a corrupt enum value collapses to [OutlawState.CLEAN]
     * rather than throwing. The decay sweep will reset it on the next pass
     * if the score is non-zero, otherwise the agent silently lands clean.
     */
    private fun parseOutlawState(raw: String): OutlawState =
        runCatching { OutlawState.valueOf(raw) }.getOrDefault(OutlawState.CLEAN)

    private fun adjustReputation(agentId: AgentId, delta: Int, column: TableField<AgentsRecord, Int?>): Int? {
        val record = lockAgentRow(agentId) ?: return null
        val current = record[column]!!
        // Long-arithmetic saturate: keeps a future raiser that hands Int.MAX_VALUE-ish deltas
        // from wrapping the column past its bounds before the write lands.
        val updated = (current.toLong() + delta.toLong())
            .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
            .toInt()
        dsl.update(AGENTS)
            .set(column, updated)
            .where(AGENTS.ID.eq(agentId.id))
            .execute()
        return updated
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
        offeredClasses = toClassOfferOrNull(),
        offeredEvolutions = toEvolutionOfferOrNull(),
        authority = this[AGENTS.AUTHORITY]!!,
        fame = this[AGENTS.FAME]!!,
        outlawState = parseOutlawState(this[AGENTS.OUTLAW_STATE]!!),
        outlawMisconductScore = this[AGENTS.OUTLAW_MISCONDUCT_SCORE]!!,
    )

    private companion object {
        const val INITIAL_LEVEL = 1
        const val INITIAL_XP_CURRENT = 0
        const val INITIAL_XP_TO_NEXT = 100
        const val INITIAL_UNSPENT_POINTS = 5
        const val XP_PER_LEVEL = 100
        const val UNSPENT_PER_LEVEL_UP = 5
        const val LEVEL_TEN_PENDING_CHOICE_CAP = 10
        const val LEVEL_FIFTY_EVOLUTION_CAP = 50
        val ATTRIBUTE_MILESTONES = listOf(50, 100, 200)
    }
}
