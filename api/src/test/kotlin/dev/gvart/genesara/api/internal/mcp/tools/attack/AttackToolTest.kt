package dev.gvart.genesara.api.internal.mcp.tools.attack

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityRegistry
import dev.gvart.genesara.api.internal.mcp.tools.CommandAckKind
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

class AttackToolTest {

    private val attacker = AgentId(UUID.randomUUID())
    private val target = AgentId(UUID.randomUUID())
    private val clock = MutableTestClock(Instant.parse("2026-01-01T00:00:00Z"))
    private val activity = AgentActivityRegistry(clock)
    private val gateway = RecordingGateway()
    private val tickClock = StubTickClock(currentTick = 50L)
    private val toolContext = ToolContext(emptyMap())

    @BeforeEach fun setUp() = AgentContextHolder.set(attacker)
    @AfterEach fun tearDown() = AgentContextHolder.clear()

    @Test
    fun `queues an AttackTarget command at the next tick and returns the ack`() {
        val tool = AttackTool(gateway, tickClock, activity)

        val response = tool.invoke(target.id, toolContext)

        assertEquals(CommandAckKind.QUEUED, response.kind)
        assertEquals(target.id, response.targetAgentId)
        assertEquals(51L, response.appliesAtTick)
        val (cmd, appliesAt) = gateway.submissions.single()
        val attack = assertNotNull(cmd as? WorldCommand.AttackTarget)
        assertEquals(attacker, attack.agent)
        assertEquals(target, attack.target)
        assertEquals(51L, appliesAt)
        assertEquals(attack.commandId, response.commandId)
    }

    @Test
    fun `touches activity registry on every successful invocation`() {
        val tool = AttackTool(gateway, tickClock, activity)

        assertTrue(attacker !in activity.staleAgents(clock.instant().minusSeconds(60)))

        tool.invoke(target.id, toolContext)

        assertTrue(attacker in activity.staleAgents(clock.instant().plusSeconds(60)))
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
