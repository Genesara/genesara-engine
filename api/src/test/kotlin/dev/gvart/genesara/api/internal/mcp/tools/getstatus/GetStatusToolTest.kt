package dev.gvart.genesara.api.internal.mcp.tools.getstatus

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityRegistry
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.account.PlayerId
import dev.gvart.genesara.player.Agent
import dev.gvart.genesara.player.AgentAttributes
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.player.RaceId
import dev.gvart.genesara.world.BodyView
import dev.gvart.genesara.world.Node
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.Region
import dev.gvart.genesara.world.RegionId
import dev.gvart.genesara.world.WorldQueryGateway
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.ai.chat.model.ToolContext
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GetStatusToolTest {

    private val agentId = AgentId(UUID.randomUUID())
    private val owner = PlayerId(UUID.randomUUID())
    private val agent = Agent(
        id = agentId,
        owner = owner,
        name = "Komar",
        apiToken = "tok",
        race = RaceId("human_steppe"),
        level = 3,
        xpCurrent = 42,
        xpToNext = 100,
        unspentAttributePoints = 5,
        attributes = AgentAttributes(
            strength = 4,
            dexterity = 6,
            constitution = 5,
            perception = 7,
            intelligence = 3,
            luck = 2,
        ),
    )
    private val body = BodyView(hp = 80, maxHp = 100, stamina = 35, maxStamina = 60, mana = 5, maxMana = 15)
    private val node = NodeId(99L)

    private val clock = MutableTestClock(Instant.parse("2026-01-01T00:00:00Z"))
    private val activity = AgentActivityRegistry(clock)
    private val tickClock = StubTickClock(currentTick = 200L)
    private val toolContext = ToolContext(emptyMap())

    @BeforeEach fun setUp() = AgentContextHolder.set(agentId)
    @AfterEach fun tearDown() = AgentContextHolder.clear()

    @Test
    fun `returns the full character snapshot for an active agent`() {
        val tool = GetStatusTool(
            agents = StubRegistry(agent),
            world = StubQuery(active = node, body = body),
            engine = tickClock,
            activity = activity,
        )

        val res = tool.invoke(GetStatusRequest(), toolContext)

        assertEquals(agentId.id.toString(), res.agentId)
        assertEquals("Komar", res.name)
        assertEquals("human_steppe", res.race)
        assertEquals(3, res.level)
        assertEquals(XpView(current = 42, toNext = 100), res.xp)
        assertEquals(
            AttributesView(
                strength = 4, dexterity = 6, constitution = 5,
                perception = 7, intelligence = 3, luck = 2,
            ),
            res.attributes,
        )
        assertEquals(5, res.unspentAttributePoints)
        assertEquals(PoolView(80, 100), res.hp)
        assertEquals(PoolView(35, 60), res.stamina)
        assertEquals(PoolView(5, 15), res.mana)
        assertEquals(node.value, res.location)
        assertEquals(200L, res.tick)
        assertEquals(emptyList(), res.activeEffects)
    }

    @Test
    fun `falls back to last known location when not currently active`() {
        val tool = GetStatusTool(
            agents = StubRegistry(agent),
            world = StubQuery(active = null, lastLocation = node, body = body),
            engine = tickClock,
            activity = activity,
        )

        val res = tool.invoke(GetStatusRequest(), toolContext)

        assertEquals(node.value, res.location)
    }

    @Test
    fun `reports null location and zero pools when agent has never spawned`() {
        val tool = GetStatusTool(
            agents = StubRegistry(agent),
            world = StubQuery(active = null, lastLocation = null, body = null),
            engine = tickClock,
            activity = activity,
        )

        val res = tool.invoke(GetStatusRequest(), toolContext)

        assertNull(res.location)
        assertEquals(PoolView(0, 0), res.hp)
        assertEquals(PoolView(0, 0), res.stamina)
        assertEquals(PoolView(0, 0), res.mana)
    }

    @Test
    fun `errors when the agent is not registered`() {
        val tool = GetStatusTool(
            agents = StubRegistry(null),
            world = StubQuery(),
            engine = tickClock,
            activity = activity,
        )

        assertThrows<IllegalStateException> { tool.invoke(GetStatusRequest(), toolContext) }
    }

    private class StubRegistry(private val agent: Agent?) : AgentRegistry {
        override fun find(id: AgentId): Agent? = agent
        override fun findByToken(token: String): Agent? = agent
        override fun listForOwner(owner: PlayerId): List<Agent> = listOfNotNull(agent)
    }

    private class StubQuery(
        private val active: NodeId? = null,
        private val lastLocation: NodeId? = null,
        private val body: BodyView? = null,
    ) : WorldQueryGateway {
        override fun locationOf(agent: AgentId): NodeId? = lastLocation
        override fun activePositionOf(agent: AgentId): NodeId? = active
        override fun node(id: NodeId): Node? = null
        override fun region(id: RegionId): Region? = null
        override fun nodesWithin(origin: NodeId, radius: Int): Set<NodeId> = emptySet()
        override fun randomSpawnableNode(): NodeId? = null
        override fun starterNodeFor(race: RaceId): NodeId? = null
        override fun bodyOf(agent: AgentId): BodyView? = body
    }

    private class StubTickClock(private val currentTick: Long) : TickClock {
        override fun currentTick(): Long = currentTick
    }

    private class MutableTestClock(private var now: Instant) : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
    }
}
