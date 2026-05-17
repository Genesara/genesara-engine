package dev.gvart.genesara.api.internal.mcp.tools.relationships

import dev.gvart.genesara.account.PlayerId
import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityRegistry
import dev.gvart.genesara.player.Agent
import dev.gvart.genesara.player.AgentAttributes
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.player.RelationshipAdjustmentOutcome
import dev.gvart.genesara.player.RelationshipRow
import dev.gvart.genesara.player.RelationshipsGateway
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.model.ToolContext
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GetRelationshipsToolTest {

    private val agent = AgentId(UUID.randomUUID())
    private val ally = AgentId(UUID.randomUUID())
    private val enemy = AgentId(UUID.randomUUID())
    private val stranger = AgentId(UUID.randomUUID())
    private val clock = MutableTestClock(Instant.parse("2026-01-01T00:00:00Z"))
    private val activity = AgentActivityRegistry(clock)
    private val toolContext = ToolContext(emptyMap())

    @BeforeEach fun setUp() = AgentContextHolder.set(agent)
    @AfterEach fun tearDown() = AgentContextHolder.clear()

    @Test
    fun `surfaces the agent's own authority and fame`() {
        val tool = buildTool(self = stubAgent(authority = 12, fame = 7), pairs = emptyMap())
        val res = tool.invoke(toolContext)

        assertEquals(12, res.authority)
        assertEquals(7, res.fame)
        assertTrue(res.entries.isEmpty())
    }

    @Test
    fun `entries sort by score descending — closest allies first`() {
        val tool = buildTool(
            self = stubAgent(),
            pairs = mapOf(
                ally to RelationshipRow(score = 30, lastChangedAtTick = 100L),
                stranger to RelationshipRow(score = 0, lastChangedAtTick = 50L),
                enemy to RelationshipRow(score = -45, lastChangedAtTick = 200L),
            ),
        )

        val ids = tool.invoke(toolContext).entries.map { it.agentId }

        assertEquals(
            listOf(prefixedAgentId(ally), prefixedAgentId(stranger), prefixedAgentId(enemy)),
            ids,
            "highest score first, lowest last",
        )
    }

    @Test
    fun `each entry carries score and last_changed_at_tick`() {
        val tool = buildTool(
            self = stubAgent(),
            pairs = mapOf(ally to RelationshipRow(score = 25, lastChangedAtTick = 999L)),
        )

        val entry = tool.invoke(toolContext).entries.single()

        assertEquals(prefixedAgentId(ally), entry.agentId)
        assertEquals(25, entry.score)
        assertEquals(999L, entry.lastChangedAtTick)
    }

    private fun stubAgent(authority: Int = 0, fame: Int = 0): Agent = Agent(
        id = agent,
        owner = PlayerId(UUID.randomUUID()),
        name = "tester",
        attributes = AgentAttributes(),
        authority = authority,
        fame = fame,
    )

    private fun buildTool(self: Agent, pairs: Map<AgentId, RelationshipRow>) = GetRelationshipsTool(
        relationships = StubGateway(pairs),
        agents = StubRegistry(mapOf(self.id to self)),
        activity = activity,
    )

    private fun prefixedAgentId(id: AgentId): String = "agent:${id.id}"

    private class StubGateway(private val byAgent: Map<AgentId, RelationshipRow>) : RelationshipsGateway {
        override fun adjust(a: AgentId, b: AgentId, delta: Int, tick: Long): RelationshipAdjustmentOutcome =
            error("not used")
        override fun adjustMany(anchor: AgentId, others: Collection<AgentId>, delta: Int, tick: Long) =
            error("not used")
        override fun find(a: AgentId, b: AgentId): RelationshipRow? = null
        override fun scoresFor(agentId: AgentId): Map<AgentId, RelationshipRow> = byAgent
    }

    private class StubRegistry(private val byId: Map<AgentId, Agent>) : AgentRegistry {
        override fun find(id: AgentId): Agent? = byId[id]
        override fun listForOwner(owner: PlayerId): List<Agent> = error("not used")
    }

    private class MutableTestClock(private var now: Instant) : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
    }
}
