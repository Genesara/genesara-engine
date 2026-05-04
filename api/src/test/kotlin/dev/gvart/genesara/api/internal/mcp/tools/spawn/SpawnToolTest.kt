package dev.gvart.genesara.api.internal.mcp.tools.spawn

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityRegistry
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.player.AgentId
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

class SpawnToolTest {

    private val agentId = AgentId(UUID.randomUUID())
    private val clock = MutableTestClock(Instant.parse("2026-01-01T00:00:00Z"))
    private val activity = AgentActivityRegistry(clock)
    private val gateway = RecordingGateway()
    private val tickClock = StubTickClock(currentTick = 100L)
    private val toolContext = ToolContext(emptyMap())

    @BeforeEach fun setUp() = AgentContextHolder.set(agentId)
    @AfterEach fun tearDown() = AgentContextHolder.clear()

    @Test
    fun `queues a SpawnAgent command at the next tick and returns the ack`() {
        val tool = SpawnTool(gateway, tickClock, activity)

        val response = tool.invoke(SpawnRequest(), toolContext)

        assertEquals(101L, response.appliesAtTick)
        val (cmd, appliesAt) = gateway.submissions.single()
        val spawn = assertNotNull(cmd as? WorldCommand.SpawnAgent)
        assertEquals(agentId, spawn.agent)
        assertEquals(101L, appliesAt)
        assertEquals(spawn.commandId, response.commandId)
    }

    @Test
    fun `accepts a null request body — the tool takes no parameters`() {
        val tool = SpawnTool(gateway, tickClock, activity)

        val response = tool.invoke(null, toolContext)

        assertEquals(101L, response.appliesAtTick)
        assertEquals(1, gateway.submissions.size)
    }

    @Test
    fun `touches activity registry on every successful invocation`() {
        val tool = SpawnTool(gateway, tickClock, activity)

        assertTrue(agentId !in activity.staleAgents(clock.instant().minusSeconds(60)))

        tool.invoke(SpawnRequest(), toolContext)

        assertTrue(agentId in activity.staleAgents(clock.instant().plusSeconds(60)))
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
