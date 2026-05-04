package dev.gvart.genesara.api.internal.mcp.tools.harvest

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityRegistry
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.WorldCommandGateway
import dev.gvart.genesara.world.commands.WorldCommand
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HarvestToolTest {

    private val agent = AgentId(UUID.randomUUID())
    private val clock = MutableTestClock(Instant.parse("2026-01-01T00:00:00Z"))
    private val activity = AgentActivityRegistry(clock)
    private val gateway = RecordingGateway()
    private val tickClock = StubTickClock(currentTick = 50L)
    private val toolContext = ToolContext(emptyMap())

    @BeforeEach fun setUp() = AgentContextHolder.set(agent)
    @AfterEach fun tearDown() = AgentContextHolder.clear()

    @Test
    fun `queues a Harvest command at the next tick and returns the ack`() {
        val tool = HarvestTool(gateway, tickClock, activity)

        val response = tool.invoke(HarvestRequest(itemId = "WOOD"), toolContext)

        assertEquals("queued", response.kind)
        assertEquals("WOOD", response.itemId)
        assertEquals(51L, response.appliesAtTick)
        val (cmd, appliesAt) = gateway.submissions.single()
        val harvest = assertNotNull(cmd as? WorldCommand.Harvest)
        assertEquals(agent, harvest.agent)
        assertEquals(ItemId("WOOD"), harvest.item)
        assertEquals(51L, appliesAt)
        assertEquals(harvest.commandId, response.commandId)
    }

    @Test
    fun `accepts a previously-mining-only item — single verb covers every harvest`() {
        val tool = HarvestTool(gateway, tickClock, activity)

        val response = tool.invoke(HarvestRequest(itemId = "STONE"), toolContext)

        assertEquals("queued", response.kind)
        assertEquals("STONE", response.itemId)
        val cmd = gateway.submissions.single().first as WorldCommand.Harvest
        assertEquals(ItemId("STONE"), cmd.item)
    }

    @Test
    fun `touches activity registry on every successful invocation`() {
        val tool = HarvestTool(gateway, tickClock, activity)

        assertTrue(agent !in activity.staleAgents(clock.instant().minusSeconds(60)))

        tool.invoke(HarvestRequest(itemId = "WOOD"), toolContext)

        assertTrue(agent in activity.staleAgents(clock.instant().plusSeconds(60)))
    }

    private class RecordingGateway : WorldCommandGateway {
        val submissions = mutableListOf<Pair<WorldCommand, Long>>()
        override fun submit(command: WorldCommand, appliesAtTick: Long) {
            submissions += command to appliesAtTick
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
