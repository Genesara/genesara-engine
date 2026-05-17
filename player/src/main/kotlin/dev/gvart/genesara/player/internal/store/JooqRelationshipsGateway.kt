package dev.gvart.genesara.player.internal.store

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.RelationshipAdjustmentOutcome
import dev.gvart.genesara.player.RelationshipRow
import dev.gvart.genesara.player.RelationshipsGateway
import dev.gvart.genesara.player.RelationshipsGateway.Companion.SCORE_MAX
import dev.gvart.genesara.player.RelationshipsGateway.Companion.SCORE_MIN
import dev.gvart.genesara.player.internal.jooq.tables.references.AGENT_RELATIONSHIPS
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Component
internal class JooqRelationshipsGateway(
    private val dsl: DSLContext,
) : RelationshipsGateway {

    @Transactional
    override fun adjust(
        a: AgentId,
        b: AgentId,
        delta: Int,
        tick: Long,
    ): RelationshipAdjustmentOutcome {
        val (lo, hi) = canonicalize(a, b)
        // Single atomic upsert. PostgreSQL `ON CONFLICT DO UPDATE` serializes on the PK,
        // and the clamp is computed server-side, so two concurrent first-touch witnesses
        // both land without a read-then-write race. The INSERT branch starts the row at
        // the clamped delta (assuming previous = 0); the UPDATE branch adds to the
        // existing score and clamps again.
        val clampedDelta = delta.coerceIn(SCORE_MIN, SCORE_MAX)
        val newScore = dsl.insertInto(AGENT_RELATIONSHIPS)
            .set(AGENT_RELATIONSHIPS.AGENT_A, lo)
            .set(AGENT_RELATIONSHIPS.AGENT_B, hi)
            .set(AGENT_RELATIONSHIPS.SCORE, clampedDelta)
            .set(AGENT_RELATIONSHIPS.LAST_CHANGED_AT_TICK, tick)
            .onConflict(AGENT_RELATIONSHIPS.AGENT_A, AGENT_RELATIONSHIPS.AGENT_B)
            .doUpdate()
            .set(
                AGENT_RELATIONSHIPS.SCORE,
                DSL.greatest(
                    DSL.least(AGENT_RELATIONSHIPS.SCORE.plus(delta), DSL.inline(SCORE_MAX)),
                    DSL.inline(SCORE_MIN),
                ),
            )
            .set(AGENT_RELATIONSHIPS.LAST_CHANGED_AT_TICK, tick)
            .returningResult(AGENT_RELATIONSHIPS.SCORE)
            .fetchOne()!!
            .value1()!!
        return RelationshipAdjustmentOutcome(currentScore = newScore)
    }

    @Transactional
    override fun adjustMany(anchor: AgentId, others: Collection<AgentId>, delta: Int, tick: Long) {
        val deduped = others.toSet().filter { it != anchor }
        if (deduped.isEmpty()) return
        val clampedDelta = delta.coerceIn(SCORE_MIN, SCORE_MAX)
        val update = DSL.greatest(
            DSL.least(AGENT_RELATIONSHIPS.SCORE.plus(delta), DSL.inline(SCORE_MAX)),
            DSL.inline(SCORE_MIN),
        )
        val batch = dsl.insertInto(
            AGENT_RELATIONSHIPS,
            AGENT_RELATIONSHIPS.AGENT_A,
            AGENT_RELATIONSHIPS.AGENT_B,
            AGENT_RELATIONSHIPS.SCORE,
            AGENT_RELATIONSHIPS.LAST_CHANGED_AT_TICK,
        )
        deduped.forEach { other ->
            val (lo, hi) = canonicalize(anchor, other)
            batch.values(lo, hi, clampedDelta, tick)
        }
        batch.onConflict(AGENT_RELATIONSHIPS.AGENT_A, AGENT_RELATIONSHIPS.AGENT_B)
            .doUpdate()
            .set(AGENT_RELATIONSHIPS.SCORE, update)
            .set(AGENT_RELATIONSHIPS.LAST_CHANGED_AT_TICK, tick)
            .execute()
    }

    @Transactional(readOnly = true)
    override fun find(a: AgentId, b: AgentId): RelationshipRow? {
        val (lo, hi) = canonicalize(a, b)
        return dsl.select(AGENT_RELATIONSHIPS.SCORE, AGENT_RELATIONSHIPS.LAST_CHANGED_AT_TICK)
            .from(AGENT_RELATIONSHIPS)
            .where(AGENT_RELATIONSHIPS.AGENT_A.eq(lo))
            .and(AGENT_RELATIONSHIPS.AGENT_B.eq(hi))
            .fetchOne()
            ?.let {
                RelationshipRow(
                    score = it[AGENT_RELATIONSHIPS.SCORE]!!,
                    lastChangedAtTick = it[AGENT_RELATIONSHIPS.LAST_CHANGED_AT_TICK]!!,
                )
            }
    }

    @Transactional(readOnly = true)
    override fun scoresFor(agentId: AgentId): Map<AgentId, RelationshipRow> =
        dsl.select(
            AGENT_RELATIONSHIPS.AGENT_A,
            AGENT_RELATIONSHIPS.AGENT_B,
            AGENT_RELATIONSHIPS.SCORE,
            AGENT_RELATIONSHIPS.LAST_CHANGED_AT_TICK,
        )
            .from(AGENT_RELATIONSHIPS)
            .where(AGENT_RELATIONSHIPS.AGENT_A.eq(agentId.id))
            .or(AGENT_RELATIONSHIPS.AGENT_B.eq(agentId.id))
            .orderBy(AGENT_RELATIONSHIPS.SCORE.desc())
            .fetch()
            .associate { row ->
                val rowA = row[AGENT_RELATIONSHIPS.AGENT_A]!!
                val rowB = row[AGENT_RELATIONSHIPS.AGENT_B]!!
                val other = if (rowA == agentId.id) rowB else rowA
                AgentId(other) to RelationshipRow(
                    score = row[AGENT_RELATIONSHIPS.SCORE]!!,
                    lastChangedAtTick = row[AGENT_RELATIONSHIPS.LAST_CHANGED_AT_TICK]!!,
                )
            }

    private fun canonicalize(a: AgentId, b: AgentId): Pair<UUID, UUID> {
        require(a != b) { "Relationship pair must be two distinct agents, got $a twice" }
        return if (a.id < b.id) a.id to b.id else b.id to a.id
    }
}
