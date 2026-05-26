package dev.gvart.genesara.api.internal.rest.admin.agents

import dev.gvart.genesara.admin.Admin
import dev.gvart.genesara.admin.AdminAuditLog
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.player.MisconductOutcome
import dev.gvart.genesara.player.OutlawState
import dev.gvart.genesara.player.RelationshipsGateway
import dev.gvart.genesara.world.events.SocialEvent
import dev.gvart.genesara.world.internal.balance.BalanceLookup
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@RestController
@RequestMapping("/admin/agents/{agentId}")
internal class AgentSocialController(
    private val agents: AgentRegistry,
    private val relationships: RelationshipsGateway,
    private val audit: AdminAuditLog,
    private val publisher: ApplicationEventPublisher,
    private val tick: TickClock,
    private val balance: BalanceLookup,
) {

    @PostMapping("/authority")
    fun authority(
        @AuthenticationPrincipal admin: Admin,
        @PathVariable agentId: UUID,
        @RequestBody req: ReputationRequest,
    ): ReputationResponse {
        val target = AgentId(agentId)
        val current = agents.find(target) ?: throw notFound("agent $agentId is not registered")
        val delta = resolveReputationDelta(req, currentValue = current.authority, label = "authority")
        val updated = agents.adjustAuthority(target, delta)
            ?: throw notFound("agent $agentId is not registered")
        audit.record(
            adminId = admin.id,
            action = "agent.authority",
            target = "agent",
            targetId = agentId.toString(),
            payload = mapOf("delta" to delta, "value" to updated),
            tick = tick.currentTick(),
        )
        return ReputationResponse(value = updated)
    }

    @PostMapping("/fame")
    fun fame(
        @AuthenticationPrincipal admin: Admin,
        @PathVariable agentId: UUID,
        @RequestBody req: ReputationRequest,
    ): ReputationResponse {
        val target = AgentId(agentId)
        val current = agents.find(target) ?: throw notFound("agent $agentId is not registered")
        val delta = resolveReputationDelta(req, currentValue = current.fame, label = "fame")
        val updated = agents.adjustFame(target, delta)
            ?: throw notFound("agent $agentId is not registered")
        audit.record(
            adminId = admin.id,
            action = "agent.fame",
            target = "agent",
            targetId = agentId.toString(),
            payload = mapOf("delta" to delta, "value" to updated),
            tick = tick.currentTick(),
        )
        return ReputationResponse(value = updated)
    }

    @PostMapping("/outlaw")
    fun outlaw(
        @AuthenticationPrincipal admin: Admin,
        @PathVariable agentId: UUID,
        @RequestBody req: OutlawRequest,
    ): OutlawResponse {
        val target = AgentId(agentId)
        val current = agents.find(target) ?: throw notFound("agent $agentId is not registered")
        val watchedAt = balance.outlawWatchedScore()
        val outlawAt = balance.outlawOutlawScore()
        val targetScore = when (req.state) {
            OutlawState.CLEAN -> 0
            OutlawState.WATCHED -> watchedAt
            OutlawState.OUTLAW -> outlawAt
        }
        val delta = targetScore - current.outlawMisconductScore
        val now = tick.currentTick()
        val outcome: MisconductOutcome = agents.adjustMisconduct(
            agentId = target,
            delta = delta,
            watchedAt = watchedAt,
            outlawAt = outlawAt,
        ) ?: throw notFound("agent $agentId is not registered")
        // WHY: publish before audit so a listener exception cannot leave an audit row
        // pointing at an event the dispatcher never received.
        if (outcome.didTransition) {
            publisher.publishEvent(
                SocialEvent.OutlawStateChanged(
                    agent = target,
                    previousState = outcome.oldState,
                    newState = outcome.newState,
                    score = outcome.newScore,
                    listeners = setOf(target),
                    tick = now,
                    causedBy = null,
                ),
            )
        }
        audit.record(
            adminId = admin.id,
            action = "agent.outlaw",
            target = "agent",
            targetId = agentId.toString(),
            payload = mapOf(
                "previousState" to outcome.oldState.name,
                "newState" to outcome.newState.name,
                "score" to outcome.newScore,
                "expiresAtTick" to req.expiresAtTick,
            ),
            tick = now,
        )
        return OutlawResponse(
            state = outcome.newState,
            score = outcome.newScore,
            transitioned = outcome.didTransition,
        )
    }

    @PostMapping("/relationships/{otherId}")
    fun relationship(
        @AuthenticationPrincipal admin: Admin,
        @PathVariable agentId: UUID,
        @PathVariable otherId: UUID,
        @RequestBody req: RelationshipRequest,
    ): RelationshipResponse {
        if (agentId == otherId) throw badRequest("cannot edit relationship with self")
        val a = AgentId(agentId)
        val b = AgentId(otherId)
        if (agents.find(a) == null) throw notFound("agent $agentId is not registered")
        if (agents.find(b) == null) throw notFound("agent $otherId is not registered")

        val now = tick.currentTick()
        val delta = resolveRelationshipDelta(req, current = relationships.find(a, b)?.score ?: 0)
        val updated = relationships.adjust(a, b, delta, now).currentScore
        audit.record(
            adminId = admin.id,
            action = "agent.relationship",
            target = "agent_pair",
            targetId = "$agentId:$otherId",
            payload = mapOf(
                "agentId" to agentId.toString(),
                "otherId" to otherId.toString(),
                "delta" to delta,
                "value" to updated,
            ),
            tick = now,
        )
        return RelationshipResponse(score = updated, bidirectional = true)
    }

    private fun resolveReputationDelta(req: ReputationRequest, currentValue: Int, label: String): Int {
        val value = req.value
        val delta = req.delta
        if ((value == null) == (delta == null)) {
            throw badRequest("$label: exactly one of `value` or `delta` is required")
        }
        return delta ?: ((value!!.toLong() - currentValue.toLong())
            .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
            .toInt())
    }

    private fun resolveRelationshipDelta(req: RelationshipRequest, current: Int): Int {
        val score = req.score
        val delta = req.delta
        if ((score == null) == (delta == null)) {
            throw badRequest("relationship: exactly one of `score` or `delta` is required")
        }
        if (score != null && score !in RelationshipsGateway.SCORE_MIN..RelationshipsGateway.SCORE_MAX) {
            throw badRequest(
                "relationship score must be in ${RelationshipsGateway.SCORE_MIN}..${RelationshipsGateway.SCORE_MAX}",
            )
        }
        return delta ?: (score!! - current)
    }

    private fun badRequest(detail: String) = ResponseStatusException(HttpStatus.BAD_REQUEST, detail)
    private fun notFound(detail: String) = ResponseStatusException(HttpStatus.NOT_FOUND, detail)
}

/**
 * Either-or: `value` sets an absolute target, `delta` applies a relative
 * adjustment. WHY: `value` is implemented as snapshot-and-add against the
 * read on entry — under concurrent admin edits or in-flight raisers the
 * landed value can drift. Operators editing the same agent need to serialize.
 */
data class ReputationRequest(
    val value: Int? = null,
    val delta: Int? = null,
)

data class ReputationResponse(
    val value: Int,
)

/**
 * Direct state-set on the outlaw machine. WHY: implemented as a snapshot
 * read followed by a delta into the score-driven gateway, so a concurrent
 * `OutlawDecaySweep` tick or an AttackReducer accrual between the read and
 * the write can land the agent in a different state than requested. The
 * audit row + the emitted `OutlawStateChanged` event reflect the *actual*
 * landed state, not the requested one — operators must re-check on
 * conflict.
 */
data class OutlawRequest(
    val state: OutlawState,
    // TODO(#206-followup): persist `expiresAtTick` once the outlaw state machine grows an
    //  expiry column; today the score-driven decay sweep is the only path to natural CLEAN.
    val expiresAtTick: Long? = null,
)

data class OutlawResponse(
    val state: OutlawState,
    val score: Int,
    val transitioned: Boolean,
)

data class RelationshipRequest(
    val score: Int? = null,
    val delta: Int? = null,
)

data class RelationshipResponse(
    val score: Int,
    val bidirectional: Boolean,
)
