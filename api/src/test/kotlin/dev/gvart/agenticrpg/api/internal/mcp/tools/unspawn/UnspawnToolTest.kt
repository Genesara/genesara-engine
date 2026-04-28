package dev.gvart.agenticrpg.api.internal.mcp.tools.unspawn

import dev.gvart.agenticrpg.api.internal.mcp.presence.AgentActivityRegistry
import dev.gvart.agenticrpg.api.internal.mcp.context.AgentContextHolder
import dev.gvart.agenticrpg.engine.TickClock
import dev.gvart.agenticrpg.player.AgentId
import dev.gvart.agenticrpg.world.WorldCommandGateway
import dev.gvart.agenticrpg.world.commands.WorldCommand
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

class UnspawnToolTest {

    private val agent = AgentId(UUID.randomUUID())

    private val clock = MutableTestClock(Instant.parse("2026-01-01T00:00:00Z"))
    private val activity = AgentActivityRegistry(clock)
    private val gateway = RecordingGateway()
    private val tickClock = StubTickClock(currentTick = 200L)
    private val toolContext = ToolContext(emptyMap())

    private val tool = UnspawnTool(gateway, tickClock, activity)

    @BeforeEach
    fun setUp() {
        AgentContextHolder.set(agent)
    }

    @AfterEach
    fun tearDown() {
        AgentContextHolder.clear()
    }

    @Test
    fun `submits an UnspawnAgent command at the next tick`() {
        val response = tool.invoke(UnspawnRequest(), toolContext)

        val (cmd, appliesAt) = gateway.submissions.single()
        val unspawn = assertNotNull(cmd as? WorldCommand.UnspawnAgent)
        assertEquals(agent, unspawn.agent)
        assertEquals(201L, appliesAt)
        assertEquals(unspawn.commandId, response.commandId)
        assertEquals(201L, response.appliesAtTick)
    }

    @Test
    fun `forgets the agent in the activity registry`() {
        // First, prove the registry knows the agent (after invoke touches then forgets,
        // the agent must NOT appear stale relative to any cutoff because it's been removed entirely).
        tool.invoke(UnspawnRequest(), toolContext)

        val futureCutoff = clock.instant().plusSeconds(60)
        assertTrue(activity.staleAgents(futureCutoff).isEmpty())
    }

    @Test
    fun `touches activity before forgetting (touch + forget are both invoked)`() {
        // Pre-seed: the agent exists in the registry. After invoke, it's gone.
        activity.touch(agent)
        assertTrue(agent in activity.staleAgents(clock.instant().plusSeconds(60)))

        tool.invoke(UnspawnRequest(), toolContext)

        assertTrue(activity.staleAgents(clock.instant().plusSeconds(60)).isEmpty())
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
