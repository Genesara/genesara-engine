package dev.gvart.genesara.api.internal.mcp.tools.attributes

import dev.gvart.genesara.account.PlayerId
import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityRegistry
import dev.gvart.genesara.api.internal.mcp.tools.equipment.DerivedPoolsRefresher
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.player.Agent
import dev.gvart.genesara.player.AgentAttributes
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.player.AllocateAttributesOutcome
import dev.gvart.genesara.player.Attribute
import dev.gvart.genesara.player.AttributeMilestoneCrossing
import dev.gvart.genesara.player.MaxPools
import dev.gvart.genesara.player.events.AgentEvent
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.model.ToolContext
import org.springframework.context.ApplicationEventPublisher
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AllocatePointsToolTest {

    private val agent = AgentId(UUID.randomUUID())
    private val clock = MutableTestClock(Instant.parse("2026-01-01T00:00:00Z"))
    private val activity = AgentActivityRegistry(clock)
    private val toolContext = ToolContext(emptyMap())
    private val tickClock = StubTickClock(currentTick = 42L)

    @BeforeEach fun setUp() = AgentContextHolder.set(agent)
    @AfterEach fun tearDown() = AgentContextHolder.clear()

    private fun allocated(
        attrs: AgentAttributes,
        remainingUnspent: Int = 0,
        crossings: List<AttributeMilestoneCrossing> = emptyList(),
        pools: MaxPools = MaxPools(maxHp = 60, maxStamina = 40, maxMana = 5),
    ) = AllocateAttributesOutcome.Allocated(
        attributes = attrs,
        remainingUnspent = remainingUnspent,
        crossedMilestones = crossings,
        pools = pools,
    )

    @Test
    fun `happy path returns ok and publishes one event per crossed milestone`() {
        val crossings = listOf(
            AttributeMilestoneCrossing(Attribute.INTELLIGENCE, 50),
            AttributeMilestoneCrossing(Attribute.INTELLIGENCE, 100),
        )
        val newAttrs = AgentAttributes(intelligence = 100)
        val registry = RecordingRegistry(returns = allocated(newAttrs, crossings = crossings))
        val publisher = RecordingPublisher()
        val refresher = RecordingRefresher()
        val tool = AllocatePointsTool(registry, activity, publisher, tickClock, refresher)

        val response = tool.invoke(mapOf(Attribute.INTELLIGENCE to 99), toolContext)

        assertEquals(AllocatePointsKind.OK, response.kind)
        assertEquals(0, response.remainingUnspent)
        assertEquals(100, response.attributes?.get(Attribute.INTELLIGENCE))
        assertEquals(2, response.crossedMilestones?.size)

        val events = publisher.events.filterIsInstance<AgentEvent.AttributeMilestoneReached>()
        assertEquals(2, events.size)
        assertTrue(events.all { it.tick == 42L && it.agent == agent && it.attribute == Attribute.INTELLIGENCE })
        assertEquals(setOf(50, 100), events.map { it.milestone }.toSet())
    }

    @Test
    fun `successful allocation routes pool recompute through DerivedPoolsRefresher`() {
        val registry = RecordingRegistry(
            returns = allocated(
                attrs = AgentAttributes(constitution = 5, intelligence = 3),
                remainingUnspent = 0,
            ),
        )
        val refresher = RecordingRefresher()
        val tool = AllocatePointsTool(registry, activity, RecordingPublisher(), tickClock, refresher)

        tool.invoke(mapOf(Attribute.CONSTITUTION to 4, Attribute.INTELLIGENCE to 2), toolContext)

        assertEquals(listOf(agent), refresher.refreshed)
    }

    @Test
    fun `no crossings means no events even on success`() {
        val registry = RecordingRegistry(returns = allocated(AgentAttributes(strength = 4), remainingUnspent = 2))
        val publisher = RecordingPublisher()

        AllocatePointsTool(registry, activity, publisher, tickClock, RecordingRefresher())
            .invoke(mapOf(Attribute.STRENGTH to 3), toolContext)

        assertTrue(publisher.events.isEmpty())
    }

    @Test
    fun `negative delta from registry maps to NEGATIVE_DELTA reason and skips the gateway`() {
        val registry = RecordingRegistry(returns = AllocateAttributesOutcome.NegativeDelta)
        val refresher = RecordingRefresher()

        val response = AllocatePointsTool(registry, activity, RecordingPublisher(), tickClock, refresher)
            .invoke(mapOf(Attribute.STRENGTH to 1), toolContext)

        assertEquals(AllocatePointsKind.REJECTED, response.kind)
        assertEquals(AllocatePointsRejectionReason.NEGATIVE_DELTA, response.reason)
        assertTrue(refresher.refreshed.isEmpty())
    }

    @Test
    fun `insufficient points reports unspent and requested in detail`() {
        val registry = RecordingRegistry(returns = AllocateAttributesOutcome.InsufficientPoints(unspent = 2, requested = 5L))

        val response = AllocatePointsTool(registry, activity, RecordingPublisher(), tickClock, RecordingRefresher())
            .invoke(mapOf(Attribute.STRENGTH to 5), toolContext)

        assertEquals(AllocatePointsKind.REJECTED, response.kind)
        assertEquals(AllocatePointsRejectionReason.INSUFFICIENT_POINTS, response.reason)
        val detail = assertNotNull(response.detail)
        assertTrue("2" in detail)
        assertTrue("5" in detail)
    }

    @Test
    fun `empty deltas short-circuits to NO_OP without touching the registry`() {
        val registry = RecordingRegistry(returns = AllocateAttributesOutcome.NegativeDelta)
        val refresher = RecordingRefresher()

        val response = AllocatePointsTool(registry, activity, RecordingPublisher(), tickClock, refresher)
            .invoke(emptyMap(), toolContext)

        assertEquals(AllocatePointsKind.REJECTED, response.kind)
        assertEquals(AllocatePointsRejectionReason.NO_OP, response.reason)
        assertTrue(registry.calls.isEmpty())
        assertTrue(refresher.refreshed.isEmpty())
    }

    @Test
    fun `all-zero deltas short-circuits to NO_OP without touching the registry`() {
        val registry = RecordingRegistry(returns = AllocateAttributesOutcome.NegativeDelta)
        val refresher = RecordingRefresher()

        val response = AllocatePointsTool(registry, activity, RecordingPublisher(), tickClock, refresher)
            .invoke(mapOf(Attribute.STRENGTH to 0, Attribute.DEXTERITY to 0), toolContext)

        assertEquals(AllocatePointsKind.REJECTED, response.kind)
        assertEquals(AllocatePointsRejectionReason.NO_OP, response.reason)
        assertTrue(registry.calls.isEmpty())
        assertTrue(refresher.refreshed.isEmpty())
    }

    @Test
    fun `null registry result becomes AGENT_MISSING`() {
        val registry = RecordingRegistry(returns = null)

        val response = AllocatePointsTool(registry, activity, RecordingPublisher(), tickClock, RecordingRefresher())
            .invoke(mapOf(Attribute.STRENGTH to 1), toolContext)

        assertEquals(AllocatePointsKind.REJECTED, response.kind)
        assertEquals(AllocatePointsRejectionReason.AGENT_MISSING, response.reason)
    }

    @Test
    fun `touchActivity records the agent on every entry`() {
        val registry = RecordingRegistry(returns = allocated(AgentAttributes(), remainingUnspent = 4))

        AllocatePointsTool(registry, activity, RecordingPublisher(), tickClock, RecordingRefresher())
            .invoke(mapOf(Attribute.STRENGTH to 1), toolContext)

        assertEquals(clock.instant(), activity.lastActiveAt(agent))
    }

    private class RecordingRegistry(private val returns: AllocateAttributesOutcome?) : AgentRegistry {
        val calls = mutableListOf<Pair<AgentId, Map<Attribute, Int>>>()
        override fun find(id: AgentId): Agent? = null
        override fun listForOwner(owner: PlayerId): List<Agent> = emptyList()
        override fun allocateAttributes(
            agentId: AgentId,
            deltas: Map<Attribute, Int>,
        ): AllocateAttributesOutcome? {
            calls += agentId to deltas
            return returns
        }
    }

    private class RecordingPublisher : ApplicationEventPublisher {
        val events = mutableListOf<Any>()
        override fun publishEvent(event: Any) {
            events += event
        }
    }

    private class RecordingRefresher : DerivedPoolsRefresher {
        val refreshed = mutableListOf<AgentId>()
        override fun refresh(agent: AgentId) { refreshed += agent }
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
