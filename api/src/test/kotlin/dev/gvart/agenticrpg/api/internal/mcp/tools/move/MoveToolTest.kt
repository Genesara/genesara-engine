package dev.gvart.agenticrpg.api.internal.mcp.tools.move

import dev.gvart.agenticrpg.api.internal.mcp.context.AgentContextHolder
import dev.gvart.agenticrpg.api.internal.mcp.presence.AgentActivityRegistry
import dev.gvart.agenticrpg.engine.TickClock
import dev.gvart.agenticrpg.player.AgentId
import dev.gvart.agenticrpg.world.NodeId
import dev.gvart.agenticrpg.world.WorldCommandGateway
import dev.gvart.agenticrpg.world.commands.WorldCommand
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MoveToolTest {

    private val agent = AgentId(UUID.randomUUID())
    private val target = NodeId(99L)

    private val clock = MutableTestClock(Instant.parse("2026-01-01T00:00:00Z"))
    private val activity = AgentActivityRegistry(clock)
    private val gateway = RecordingGateway()
    private val tickClock = StubTickClock(currentTick = 500L)
    private val toolContext = ToolContext(emptyMap())

    private val tool = MoveTool(gateway, tickClock, activity)

    @BeforeEach
    fun setUp() {
        AgentContextHolder.set(agent)
    }

    @AfterEach
    fun tearDown() {
        AgentContextHolder.clear()
    }

    @Test
    fun `queues a MoveAgent command for the next tick`() {
        val response = tool.invoke(MoveRequest(nodeId = target.value), toolContext)

        val (cmd, appliesAt) = gateway.submissions.single()
        val moveCmd = assertNotNull(cmd as? WorldCommand.MoveAgent)
        assertEquals(agent, moveCmd.agent)
        assertEquals(target, moveCmd.to)
        assertEquals(501L, appliesAt)
        assertEquals(moveCmd.commandId, response.commandId)
        assertEquals(501L, response.appliesAtTick)
    }

    @Test
    fun `each invocation produces a distinct commandId`() {
        val first = tool.invoke(MoveRequest(nodeId = target.value), toolContext)
        val second = tool.invoke(MoveRequest(nodeId = target.value), toolContext)

        assertEquals(2, gateway.submissions.size)
        assertTrue(first.commandId != second.commandId)
    }

    @Test
    fun `touches the activity registry on every invocation`() {
        val pastCutoff = clock.instant().minusSeconds(60)
        assertTrue(agent !in activity.staleAgents(pastCutoff))

        tool.invoke(MoveRequest(nodeId = target.value), toolContext)

        // The registry recorded a touch — agent is stale relative to a future cutoff
        // (proves a touch was made at clock.instant()), but fresh relative to a past one.
        assertTrue(agent in activity.staleAgents(clock.instant().plusSeconds(60)))
        assertTrue(agent !in activity.staleAgents(pastCutoff))
    }

    @Test
    fun `fails fast when no agent is bound to the thread`() {
        AgentContextHolder.clear()

        assertThrows<IllegalStateException> {
            tool.invoke(MoveRequest(nodeId = target.value), toolContext)
        }
        assertTrue(gateway.submissions.isEmpty())
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
