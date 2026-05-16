package dev.gvart.genesara.api.internal.mcp.tools.attack

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityRegistry
import dev.gvart.genesara.api.internal.mcp.tools.CommandAckKind
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.NpcId
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

class AttackToolTest {

    private val attacker = AgentId(UUID.randomUUID())
    private val targetAgent = AgentId(UUID.randomUUID())
    private val targetNpc = NpcId(UUID.randomUUID())
    private val clock = MutableTestClock(Instant.parse("2026-01-01T00:00:00Z"))
    private val activity = AgentActivityRegistry(clock)
    private val gateway = RecordingGateway()
    private val tickClock = StubTickClock(currentTick = 50L)
    private val toolContext = ToolContext(emptyMap())

    @BeforeEach fun setUp() = AgentContextHolder.set(attacker)
    @AfterEach fun tearDown() = AgentContextHolder.clear()

    @Test
    fun `queues an AttackTarget command for agent prefixed target`() {
        val tool = AttackTool(gateway, tickClock, activity)
        val wire = "agent:${targetAgent.id}"

        val response = tool.invoke(wire, toolContext)

        assertEquals(CommandAckKind.QUEUED, response.kind)
        assertEquals(wire, response.target)
        assertEquals(51L, response.appliesAtTick)
        val (cmd, appliesAt) = gateway.submissions.single()
        val attack = assertNotNull(cmd as? WorldCommand.AttackTarget)
        assertEquals(attacker, attack.agent)
        assertEquals(targetAgent, attack.target)
        assertEquals(51L, appliesAt)
        assertEquals(attack.commandId, response.commandId)
    }

    @Test
    fun `queues an AttackNpc command for npc prefixed target`() {
        val tool = AttackTool(gateway, tickClock, activity)
        val wire = "npc:${targetNpc.value}"

        val response = tool.invoke(wire, toolContext)

        assertEquals(CommandAckKind.QUEUED, response.kind)
        assertEquals(wire, response.target)
        val (cmd, _) = gateway.submissions.single()
        val attack = assertNotNull(cmd as? WorldCommand.AttackNpc)
        assertEquals(attacker, attack.agent)
        assertEquals(targetNpc, attack.npc)
    }

    @Test
    fun `rejects a bare UUID without the wire prefix`() {
        val tool = AttackTool(gateway, tickClock, activity)
        val rawUuid = "c2da8aef-aeb1-466d-99fd-0e38ad9ed971"

        val response = tool.invoke(rawUuid, toolContext)

        assertEquals(CommandAckKind.REJECTED, response.kind)
        assertEquals(rawUuid, response.target)
        assertEquals("bad_target_id", response.reason)
        assertNull(response.commandId)
        assertTrue(gateway.submissions.isEmpty())
    }

    @Test
    fun `rejects a malformed UUID after the prefix`() {
        val tool = AttackTool(gateway, tickClock, activity)

        val response = tool.invoke("agent:not-a-uuid", toolContext)

        assertEquals(CommandAckKind.REJECTED, response.kind)
        assertEquals("agent:not-a-uuid", response.target)
        assertEquals("bad_target_id", response.reason)
        assertNull(response.commandId)
        assertTrue(gateway.submissions.isEmpty())
    }

    @Test
    fun `rejects an unknown prefix`() {
        val tool = AttackTool(gateway, tickClock, activity)
        val wire = "boss:${UUID.randomUUID()}"

        val response = tool.invoke(wire, toolContext)

        assertEquals(CommandAckKind.REJECTED, response.kind)
        assertEquals("bad_target_id", response.reason)
        assertTrue(gateway.submissions.isEmpty())
    }

    @Test
    fun `touches activity registry on every successful invocation`() {
        val tool = AttackTool(gateway, tickClock, activity)
        assertTrue(attacker !in activity.staleAgents(clock.instant().minusSeconds(60)))

        tool.invoke("agent:${targetAgent.id}", toolContext)

        assertTrue(attacker in activity.staleAgents(clock.instant().plusSeconds(60)))
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
