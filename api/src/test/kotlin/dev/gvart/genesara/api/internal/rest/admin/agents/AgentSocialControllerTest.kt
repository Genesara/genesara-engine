package dev.gvart.genesara.api.internal.rest.admin.agents

import dev.gvart.genesara.account.PlayerId
import dev.gvart.genesara.admin.Admin
import dev.gvart.genesara.admin.AdminAuditEntry
import dev.gvart.genesara.admin.AdminAuditLog
import dev.gvart.genesara.admin.AdminId
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.player.Agent
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.player.MisconductOutcome
import dev.gvart.genesara.player.OutlawState
import dev.gvart.genesara.player.RelationshipAdjustmentOutcome
import dev.gvart.genesara.player.RelationshipRow
import dev.gvart.genesara.player.RelationshipsGateway
import dev.gvart.genesara.world.events.SocialEvent
import dev.gvart.genesara.world.internal.balance.BalanceLookup
import dev.gvart.genesara.world.internal.balance.WorldDefinitionBalanceLookup
import dev.gvart.genesara.world.internal.balance.WorldDefinitionProperties
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import org.springframework.web.server.ResponseStatusException
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentSocialControllerTest {

    private val admin = Admin(id = AdminId(UUID.randomUUID()), username = "ops")
    private val owner = PlayerId(UUID.randomUUID())
    private val targetId = AgentId(UUID.randomUUID())
    private val otherId = AgentId(UUID.randomUUID())

    private lateinit var agents: StubRegistry
    private lateinit var relationships: StubRelationships
    private lateinit var audit: RecordingAuditLog
    private lateinit var publisher: RecordingPublisher
    private lateinit var controller: AgentSocialController
    private val balance: BalanceLookup = WorldDefinitionBalanceLookup(WorldDefinitionProperties())

    @BeforeEach
    fun setup() {
        agents = StubRegistry(
            mutableMapOf(
                targetId to agent(targetId, "Hero"),
                otherId to agent(otherId, "Other"),
            ),
        )
        relationships = StubRelationships()
        audit = RecordingAuditLog()
        publisher = RecordingPublisher()
        controller = AgentSocialController(
            agents = agents,
            relationships = relationships,
            audit = audit,
            publisher = publisher,
            tick = FixedTickClock(42L),
            balance = balance,
        )
    }

    @Test
    fun `authority delta increments and audits`() {
        agents.set(agent(targetId, "Hero", authority = 5))

        val response = controller.authority(admin, targetId.id, ReputationRequest(delta = 3))

        assertEquals(8, response.value)
        assertEquals(3, agents.appliedAuthorityDelta(targetId))
        val row = audit.entries.single()
        assertEquals("agent.authority", row.action)
        assertEquals(targetId.id.toString(), row.targetId)
        assertEquals(3, row.payload["delta"])
        assertEquals(8, row.payload["value"])
    }

    @Test
    fun `authority value computes delta from current`() {
        agents.set(agent(targetId, "Hero", authority = 20))

        val response = controller.authority(admin, targetId.id, ReputationRequest(value = 50))

        assertEquals(50, response.value)
        assertEquals(30, agents.appliedAuthorityDelta(targetId))
    }

    @Test
    fun `authority rejects request without value or delta`() {
        val ex = assertFailsWith<ResponseStatusException> {
            controller.authority(admin, targetId.id, ReputationRequest())
        }
        assertTrue(ex.reason!!.contains("exactly one"))
    }

    @Test
    fun `authority rejects request with both value and delta`() {
        assertFailsWith<ResponseStatusException> {
            controller.authority(admin, targetId.id, ReputationRequest(value = 1, delta = 1))
        }
    }

    @Test
    fun `authority returns 404 for an unknown agent`() {
        val unknown = UUID.randomUUID()
        val ex = assertFailsWith<ResponseStatusException> {
            controller.authority(admin, unknown, ReputationRequest(delta = 1))
        }
        assertEquals(404, ex.statusCode.value())
    }

    @Test
    fun `fame value drop and audit fields`() {
        agents.set(agent(targetId, "Hero", fame = 100))

        val response = controller.fame(admin, targetId.id, ReputationRequest(value = 5))

        assertEquals(5, response.value)
        assertEquals(-95, agents.appliedFameDelta(targetId))
        val row = audit.entries.single()
        assertEquals("agent.fame", row.action)
        assertEquals(-95, row.payload["delta"])
        assertEquals(5, row.payload["value"])
    }

    @Test
    fun `outlaw set OUTLAW emits OutlawStateChanged and audits transition`() {
        agents.set(agent(targetId, "Hero"))

        val response = controller.outlaw(admin, targetId.id, OutlawRequest(state = OutlawState.OUTLAW))

        assertEquals(OutlawState.OUTLAW, response.state)
        assertEquals(balance.outlawOutlawScore(), response.score)
        assertTrue(response.transitioned)

        val event = publisher.events.filterIsInstance<SocialEvent.OutlawStateChanged>().single()
        assertEquals(targetId, event.agent)
        assertEquals(OutlawState.CLEAN, event.previousState)
        assertEquals(OutlawState.OUTLAW, event.newState)
        assertEquals(setOf(targetId), event.listeners)
        assertNull(event.causedBy)

        val row = audit.entries.single()
        assertEquals("agent.outlaw", row.action)
        assertEquals("CLEAN", row.payload["previousState"])
        assertEquals("OUTLAW", row.payload["newState"])
    }

    @Test
    fun `outlaw CLEAN with already-clean agent does not emit and still audits`() {
        agents.set(agent(targetId, "Hero", outlawScore = 0, outlawState = OutlawState.CLEAN))

        val response = controller.outlaw(admin, targetId.id, OutlawRequest(state = OutlawState.CLEAN))

        assertEquals(OutlawState.CLEAN, response.state)
        assertEquals(false, response.transitioned)
        assertTrue(publisher.events.isEmpty())
        assertEquals(1, audit.entries.size)
    }

    @Test
    fun `outlaw clearing from OUTLAW emits transition to CLEAN`() {
        agents.set(
            agent(
                targetId,
                "Hero",
                outlawScore = balance.outlawOutlawScore(),
                outlawState = OutlawState.OUTLAW,
            ),
        )

        val response = controller.outlaw(admin, targetId.id, OutlawRequest(state = OutlawState.CLEAN))

        assertEquals(OutlawState.CLEAN, response.state)
        assertTrue(response.transitioned)
        val event = publisher.events.filterIsInstance<SocialEvent.OutlawStateChanged>().single()
        assertEquals(OutlawState.OUTLAW, event.previousState)
        assertEquals(OutlawState.CLEAN, event.newState)
    }

    @Test
    fun `outlaw expiresAtTick is captured in audit even though not enforced yet`() {
        agents.set(agent(targetId, "Hero"))

        controller.outlaw(
            admin,
            targetId.id,
            OutlawRequest(state = OutlawState.WATCHED, expiresAtTick = 500L),
        )

        assertEquals(500L, audit.entries.single().payload["expiresAtTick"])
    }

    @Test
    fun `relationship delta adjusts pair and audits both ids`() {
        relationships.seed(targetId, otherId, score = 5)

        val response = controller.relationship(
            admin,
            targetId.id,
            otherId.id,
            RelationshipRequest(delta = -10),
        )

        assertEquals(-5, response.score)
        assertTrue(response.bidirectional)
        val row = audit.entries.single()
        assertEquals("agent.relationship", row.action)
        assertEquals("${targetId.id}:${otherId.id}", row.targetId)
        assertEquals(-10, row.payload["delta"])
        assertEquals(-5, row.payload["value"])
    }

    @Test
    fun `relationship score sets absolute pair value`() {
        relationships.seed(targetId, otherId, score = -20)

        val response = controller.relationship(
            admin,
            targetId.id,
            otherId.id,
            RelationshipRequest(score = 40),
        )

        assertEquals(40, response.score)
        assertEquals(60, relationships.lastDelta)
    }

    @Test
    fun `relationship rejects self-edit with 400`() {
        val ex = assertFailsWith<ResponseStatusException> {
            controller.relationship(admin, targetId.id, targetId.id, RelationshipRequest(delta = 1))
        }
        assertEquals(400, ex.statusCode.value())
    }

    @Test
    fun `relationship rejects score outside SCORE_MIN to SCORE_MAX`() {
        assertFailsWith<ResponseStatusException> {
            controller.relationship(
                admin,
                targetId.id,
                otherId.id,
                RelationshipRequest(score = RelationshipsGateway.SCORE_MAX + 1),
            )
        }
    }

    @Test
    fun `relationship returns 404 when the other agent is unknown`() {
        val ex = assertFailsWith<ResponseStatusException> {
            controller.relationship(
                admin,
                targetId.id,
                UUID.randomUUID(),
                RelationshipRequest(delta = 1),
            )
        }
        assertEquals(404, ex.statusCode.value())
    }

    private fun agent(
        id: AgentId,
        name: String,
        authority: Int = 0,
        fame: Int = 0,
        outlawScore: Int = 0,
        outlawState: OutlawState = OutlawState.CLEAN,
    ): Agent = Agent(
        id = id,
        owner = owner,
        name = name,
        authority = authority,
        fame = fame,
        outlawMisconductScore = outlawScore,
        outlawState = outlawState,
    )

    private class StubRegistry(private val byId: MutableMap<AgentId, Agent>) : AgentRegistry {
        private val authorityDeltas = mutableMapOf<AgentId, Int>()
        private val fameDeltas = mutableMapOf<AgentId, Int>()

        fun set(agent: Agent) { byId[agent.id] = agent }
        fun appliedAuthorityDelta(id: AgentId): Int = authorityDeltas.getValue(id)
        fun appliedFameDelta(id: AgentId): Int = fameDeltas.getValue(id)

        override fun find(id: AgentId): Agent? = byId[id]
        override fun listForOwner(owner: PlayerId): List<Agent> = byId.values.filter { it.owner == owner }

        override fun adjustAuthority(agentId: AgentId, delta: Int): Int? {
            val a = byId[agentId] ?: return null
            authorityDeltas[agentId] = delta
            val updated = a.copy(authority = a.authority + delta)
            byId[agentId] = updated
            return updated.authority
        }

        override fun adjustFame(agentId: AgentId, delta: Int): Int? {
            val a = byId[agentId] ?: return null
            fameDeltas[agentId] = delta
            val updated = a.copy(fame = a.fame + delta)
            byId[agentId] = updated
            return updated.fame
        }

        override fun adjustMisconduct(
            agentId: AgentId,
            delta: Int,
            watchedAt: Int,
            outlawAt: Int,
        ): MisconductOutcome? {
            val a = byId[agentId] ?: return null
            val newScore = (a.outlawMisconductScore + delta).coerceAtLeast(0)
            val newState = OutlawState.deriveFrom(newScore, watchedAt, outlawAt)
            byId[agentId] = a.copy(outlawMisconductScore = newScore, outlawState = newState)
            return MisconductOutcome(
                agentId = agentId,
                oldScore = a.outlawMisconductScore,
                newScore = newScore,
                oldState = a.outlawState,
                newState = newState,
            )
        }
    }

    private class StubRelationships : RelationshipsGateway {
        private val rows = mutableMapOf<Pair<AgentId, AgentId>, RelationshipRow>()
        var lastDelta: Int = 0
            private set

        fun seed(a: AgentId, b: AgentId, score: Int) {
            rows[canonical(a, b)] = RelationshipRow(score, lastChangedAtTick = 0L)
        }

        override fun adjust(a: AgentId, b: AgentId, delta: Int, tick: Long): RelationshipAdjustmentOutcome {
            lastDelta = delta
            val key = canonical(a, b)
            val current = rows[key]?.score ?: 0
            val updated = (current + delta).coerceIn(RelationshipsGateway.SCORE_MIN, RelationshipsGateway.SCORE_MAX)
            rows[key] = RelationshipRow(updated, tick)
            return RelationshipAdjustmentOutcome(currentScore = updated)
        }

        override fun adjustMany(anchor: AgentId, others: Collection<AgentId>, delta: Int, tick: Long) =
            others.forEach { adjust(anchor, it, delta, tick) }

        override fun find(a: AgentId, b: AgentId): RelationshipRow? = rows[canonical(a, b)]
        override fun scoresFor(agentId: AgentId): Map<AgentId, RelationshipRow> = emptyMap()

        private fun canonical(a: AgentId, b: AgentId): Pair<AgentId, AgentId> =
            if (a.id < b.id) a to b else b to a
    }

    private class RecordingAuditLog : AdminAuditLog {
        val entries = mutableListOf<AdminAuditEntry>()
        private var seq = 0L

        override fun record(
            adminId: AdminId,
            action: String,
            target: String,
            targetId: String?,
            payload: Map<String, Any?>,
            tick: Long,
        ): Long {
            val assigned = ++seq
            entries += AdminAuditEntry(
                seq = assigned,
                adminId = adminId,
                action = action,
                target = target,
                targetId = targetId,
                payload = payload,
                tick = tick,
                occurredAt = java.time.Instant.EPOCH,
            )
            return assigned
        }

        override fun readAfter(after: Long, limit: Int): List<AdminAuditEntry> = error("not used")
    }

    private class RecordingPublisher : ApplicationEventPublisher {
        val events = mutableListOf<Any>()
        override fun publishEvent(event: Any) { events += event }
    }

    private class FixedTickClock(private val tick: Long) : TickClock {
        override fun currentTick(): Long = tick
    }
}
