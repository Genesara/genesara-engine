package dev.gvart.genesara.api.internal.mcp.tools.build

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityRegistry
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.BuildingType
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BuildToolTest {

    private val agent = AgentId(UUID.randomUUID())
    private val clock = MutableTestClock(Instant.parse("2026-01-01T00:00:00Z"))
    private val activity = AgentActivityRegistry(clock)
    private val gateway = RecordingGateway()
    private val tickClock = StubTickClock(currentTick = 50L)
    private val toolContext = ToolContext(emptyMap())

    @BeforeEach fun setUp() = AgentContextHolder.set(agent)
    @AfterEach fun tearDown() = AgentContextHolder.clear()

    @Test
    fun `queues a BuildStructure command at the next tick and returns the ack`() {
        val tool = BuildTool(gateway, tickClock, activity)

        val response = tool.invoke(BuildRequest(type = "CAMPFIRE"), toolContext)

        assertEquals("queued", response.kind)
        assertEquals("CAMPFIRE", response.type)
        assertEquals(51L, response.appliesAtTick)
        assertNull(response.error)
        val (cmd, appliesAt) = gateway.submissions.single()
        val build = assertNotNull(cmd as? WorldCommand.BuildStructure)
        assertEquals(agent, build.agent)
        assertEquals(BuildingType.CAMPFIRE, build.type)
        assertEquals(51L, appliesAt)
        assertEquals(build.commandId, response.commandId)
    }

    @Test
    fun `accepts lower-case + whitespace input via the boundary parse`() {
        val tool = BuildTool(gateway, tickClock, activity)

        val response = tool.invoke(BuildRequest(type = "  workbench  "), toolContext)

        assertEquals("queued", response.kind)
        assertEquals("WORKBENCH", response.type)
        val build = assertNotNull(gateway.submissions.single().first as? WorldCommand.BuildStructure)
        assertEquals(BuildingType.WORKBENCH, build.type)
    }

    @Test
    fun `unknown building type rejects at the boundary without queueing a command`() {
        val tool = BuildTool(gateway, tickClock, activity)

        val response = tool.invoke(BuildRequest(type = "PHANTOM_TYPE"), toolContext)

        assertEquals("error", response.kind)
        assertEquals("PHANTOM_TYPE", response.type)
        val errorMsg = assertNotNull(response.error)
        assertTrue(errorMsg.contains("CAMPFIRE"), "error must enumerate valid types so the agent can recover")
        assertNull(response.commandId)
        assertNull(response.appliesAtTick)
        assertTrue(gateway.submissions.isEmpty(), "no command should have been queued")
    }

    @Test
    fun `empty and whitespace-only type strings reject without queueing`() {
        val tool = BuildTool(gateway, tickClock, activity)

        listOf("", "   ").forEach { input ->
            val response = tool.invoke(BuildRequest(type = input), toolContext)
            assertEquals("error", response.kind, "input='$input'")
            assertNull(response.commandId, "input='$input'")
        }
        assertTrue(gateway.submissions.isEmpty())
    }

    @Test
    fun `touches activity registry on every successful invocation`() {
        val tool = BuildTool(gateway, tickClock, activity)

        assertTrue(agent !in activity.staleAgents(clock.instant().minusSeconds(60)))

        tool.invoke(BuildRequest(type = "CAMPFIRE"), toolContext)

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
