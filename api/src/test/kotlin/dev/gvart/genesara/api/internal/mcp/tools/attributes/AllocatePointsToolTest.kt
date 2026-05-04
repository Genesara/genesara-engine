package dev.gvart.genesara.api.internal.mcp.tools.attributes

import dev.gvart.genesara.account.PlayerId
import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityRegistry
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.player.Agent
import dev.gvart.genesara.player.AgentAttributes
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.player.AllocateAttributesOutcome
import dev.gvart.genesara.player.Attribute
import dev.gvart.genesara.player.AttributeMilestoneCrossing
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

    @Test
    fun `happy path returns ok and publishes one event per crossed milestone`() {
        val crossings = listOf(
            AttributeMilestoneCrossing(Attribute.INTELLIGENCE, 50),
            AttributeMilestoneCrossing(Attribute.INTELLIGENCE, 100),
        )
        val newAttrs = AgentAttributes(intelligence = 100)
        val registry = RecordingRegistry(returns = AllocateAttributesOutcome.Allocated(newAttrs, remainingUnspent = 0, crossedMilestones = crossings))
        val publisher = RecordingPublisher()
        val tool = AllocatePointsTool(registry, activity, publisher, tickClock)

        val response = tool.invoke(AllocatePointsRequest(deltas = mapOf(Attribute.INTELLIGENCE to 99)), toolContext)

        assertEquals("ok", response.kind)
        assertEquals(0, response.remainingUnspent)
        assertEquals(100, response.attributes?.get(Attribute.INTELLIGENCE))
        assertEquals(2, response.crossedMilestones?.size)

        val events = publisher.events.filterIsInstance<AgentEvent.AttributeMilestoneReached>()
        assertEquals(2, events.size)
        assertTrue(events.all { it.tick == 42L && it.agent == agent && it.attribute == Attribute.INTELLIGENCE })
        assertEquals(setOf(50, 100), events.map { it.milestone }.toSet())
    }

    @Test
    fun `no crossings means no events even on success`() {
        val registry = RecordingRegistry(
            returns = AllocateAttributesOutcome.Allocated(AgentAttributes(strength = 4), remainingUnspent = 2, crossedMilestones = emptyList()),
        )
        val publisher = RecordingPublisher()

        AllocatePointsTool(registry, activity, publisher, tickClock)
            .invoke(AllocatePointsRequest(mapOf(Attribute.STRENGTH to 3)), toolContext)

        assertTrue(publisher.events.isEmpty())
    }

    @Test
    fun `sum-zero deltas reject without touching the registry`() {
        val registry = RecordingRegistry(returns = null)

        val response = AllocatePointsTool(registry, activity, RecordingPublisher(), tickClock)
            .invoke(AllocatePointsRequest(mapOf(Attribute.STRENGTH to 0, Attribute.DEXTERITY to 0)), toolContext)

        assertEquals("rejected", response.kind)
        assertEquals("no_points_requested", response.reason)
        assertTrue(registry.calls.isEmpty())
    }

    @Test
    fun `negative delta is rejected by the tool before the registry is touched`() {
        val registry = RecordingRegistry(returns = null)

        val response = AllocatePointsTool(registry, activity, RecordingPublisher(), tickClock)
            .invoke(AllocatePointsRequest(mapOf(Attribute.STRENGTH to 1, Attribute.LUCK to -1)), toolContext)

        assertEquals("rejected", response.kind)
        assertEquals("negative_delta", response.reason)
        assertTrue(registry.calls.isEmpty(), "registry must not be called when a delta is negative")
    }

    @Test
    fun `registry-returned NegativeDelta is also surfaced as negative_delta`() {
        // The tool's pre-check normally short-circuits before the registry sees a
        // negative delta, so this branch is defensive — pin it so a later refactor
        // that drops the pre-check still has a working fallback.
        val registry = RecordingRegistry(returns = AllocateAttributesOutcome.NegativeDelta)

        val response = AllocatePointsTool(registry, activity, RecordingPublisher(), tickClock)
            .invoke(AllocatePointsRequest(mapOf(Attribute.STRENGTH to 1)), toolContext)

        assertEquals("rejected", response.kind)
        assertEquals("negative_delta", response.reason)
    }

    @Test
    fun `sum-zero with negative entries reports negative_delta not no_points_requested`() {
        // {STR: 2, LUCK: -2} sums to zero but contains a negative — the more accurate
        // rejection is `negative_delta`. Pin the ordering so a future refactor doesn't
        // shadow it.
        val registry = RecordingRegistry(returns = null)

        val response = AllocatePointsTool(registry, activity, RecordingPublisher(), tickClock)
            .invoke(AllocatePointsRequest(mapOf(Attribute.STRENGTH to 2, Attribute.LUCK to -2)), toolContext)

        assertEquals("negative_delta", response.reason)
    }

    @Test
    fun `insufficient points reports unspent and requested in detail`() {
        val registry = RecordingRegistry(returns = AllocateAttributesOutcome.InsufficientPoints(unspent = 2, requested = 5L))

        val response = AllocatePointsTool(registry, activity, RecordingPublisher(), tickClock)
            .invoke(AllocatePointsRequest(mapOf(Attribute.STRENGTH to 5)), toolContext)

        assertEquals("rejected", response.kind)
        assertEquals("insufficient_points", response.reason)
        val detail = assertNotNull(response.detail)
        assertTrue("2" in detail)
        assertTrue("5" in detail)
    }

    @Test
    fun `null registry result becomes agent_missing`() {
        val registry = RecordingRegistry(returns = null)

        val response = AllocatePointsTool(registry, activity, RecordingPublisher(), tickClock)
            .invoke(AllocatePointsRequest(mapOf(Attribute.STRENGTH to 1)), toolContext)

        assertEquals("rejected", response.kind)
        assertEquals("agent_missing", response.reason)
    }

    @Test
    fun `touchActivity records the agent on every entry`() {
        val registry = RecordingRegistry(
            returns = AllocateAttributesOutcome.Allocated(AgentAttributes(), remainingUnspent = 4, crossedMilestones = emptyList()),
        )

        AllocatePointsTool(registry, activity, RecordingPublisher(), tickClock)
            .invoke(AllocatePointsRequest(mapOf(Attribute.STRENGTH to 1)), toolContext)

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

    private class StubTickClock(private val currentTick: Long) : TickClock {
        override fun currentTick(): Long = currentTick
    }

    private class MutableTestClock(private var now: Instant) : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
    }
}
