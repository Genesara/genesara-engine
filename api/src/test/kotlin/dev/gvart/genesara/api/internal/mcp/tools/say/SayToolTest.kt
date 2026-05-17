package dev.gvart.genesara.api.internal.mcp.tools.say

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityRegistry
import dev.gvart.genesara.api.internal.mcp.tools.CommandAckKind
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.SayChannel
import dev.gvart.genesara.world.SpeechMode
import dev.gvart.genesara.world.WorldCommandGateway
import dev.gvart.genesara.world.commands.CoreCommand
import dev.gvart.genesara.world.commands.WorldCommand
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.model.ToolContext

class SayToolTest {

    private val agent = AgentId(UUID.randomUUID())
    private val clock = MutableTestClock(Instant.parse("2026-01-01T00:00:00Z"))
    private val activity = AgentActivityRegistry(clock)
    private val gateway = RecordingGateway()
    private val tickClock = StubTickClock(currentTick = 50L)
    private val toolContext = ToolContext(emptyMap())

    @BeforeEach fun setUp() = AgentContextHolder.set(agent)
    @AfterEach fun tearDown() = AgentContextHolder.clear()

    @Test
    fun `queues a Say command at next tick with explicit mode and channel`() {
        val tool = SayTool(gateway, tickClock, activity)

        val response = tool.invoke("hi there", SpeechMode.SCREAM, SayChannel.LOCAL, toolContext)

        assertEquals(CommandAckKind.QUEUED, response.kind)
        assertEquals(SpeechMode.SCREAM, response.mode)
        assertEquals(SayChannel.LOCAL, response.channel)
        assertEquals(51L, response.appliesAtTick)
        val (cmd, appliesAt) = gateway.submissions.single()
        val say = assertNotNull(cmd as? CoreCommand.Say)
        assertEquals(agent, say.agent)
        assertEquals("hi there", say.message)
        assertEquals(SpeechMode.SCREAM, say.mode)
        assertEquals(SayChannel.LOCAL, say.channel)
        assertEquals(51L, appliesAt)
        assertEquals(say.commandId, response.commandId)
    }

    @Test
    fun `null mode defaults to NORMAL`() {
        val tool = SayTool(gateway, tickClock, activity)

        val response = tool.invoke("hi", null, SayChannel.LOCAL, toolContext)

        assertEquals(SpeechMode.NORMAL, response.mode)
        val say = assertNotNull(gateway.submissions.single().first as? CoreCommand.Say)
        assertEquals(SpeechMode.NORMAL, say.mode)
    }

    @Test
    fun `null channel defaults to LOCAL`() {
        val tool = SayTool(gateway, tickClock, activity)

        val response = tool.invoke("hi", SpeechMode.WHISPER, null, toolContext)

        assertEquals(SayChannel.LOCAL, response.channel)
        val say = assertNotNull(gateway.submissions.single().first as? CoreCommand.Say)
        assertEquals(SayChannel.LOCAL, say.channel)
    }

    private class RecordingGateway : WorldCommandGateway {
        val submissions = mutableListOf<Pair<WorldCommand, Long>>()
        override fun submit(command: WorldCommand, appliesAtTick: Long): Long {
            submissions += command to appliesAtTick
            return appliesAtTick
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
