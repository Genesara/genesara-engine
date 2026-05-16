package dev.gvart.genesara.api.internal.mcp.tools.abilities

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityRegistry
import dev.gvart.genesara.api.internal.mcp.tools.CommandAckKind
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.player.AbilityId
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UseAbilityToolTest {

    private val agent = AgentId(UUID.randomUUID())
    private val target = AgentId(UUID.randomUUID())
    private val clock = MutableTestClock(Instant.parse("2026-01-01T00:00:00Z"))
    private val activity = AgentActivityRegistry(clock)
    private val gateway = RecordingGateway()
    private val tickClock = StubTickClock(currentTick = 50L)
    private val toolContext = ToolContext(emptyMap())

    @BeforeEach fun setUp() = AgentContextHolder.set(agent)
    @AfterEach fun tearDown() = AgentContextHolder.clear()

    @Test
    fun `queues a UseAbility command at the next tick with target id`() {
        val tool = UseAbilityTool(gateway, tickClock, activity)
        val wire = "agent:${target.id}"

        val response = tool.invoke("SWORD_POWER_STRIKE", wire, toolContext)

        assertEquals(CommandAckKind.QUEUED, response.kind)
        assertEquals("SWORD_POWER_STRIKE", response.abilityId)
        assertEquals(wire, response.targetAgentId)
        assertEquals(51L, response.appliesAtTick)
        val (cmd, appliesAt) = gateway.submissions.single()
        val use = assertNotNull(cmd as? WorldCommand.UseAbility)
        assertEquals(agent, use.agent)
        assertEquals(AbilityId("SWORD_POWER_STRIKE"), use.ability)
        assertEquals(target, use.target)
        assertEquals(51L, appliesAt)
        assertEquals(use.commandId, response.commandId)
    }

    @Test
    fun `omitting targetAgentId yields a self_or_area-shaped command`() {
        val tool = UseAbilityTool(gateway, tickClock, activity)

        val response = tool.invoke("HEAL_SELF", null, toolContext)

        assertNull(response.targetAgentId)
        val use = assertNotNull(gateway.submissions.single().first as? WorldCommand.UseAbility)
        assertNull(use.target)
    }

    @Test
    fun `malformed targetAgentId is rejected without queueing`() {
        val tool = UseAbilityTool(gateway, tickClock, activity)

        val response = tool.invoke("SWORD_POWER_STRIKE", "not-a-uuid", toolContext)

        assertEquals(CommandAckKind.REJECTED, response.kind)
        assertEquals("bad_target_agent_id", response.reason)
        assertEquals("not-a-uuid", response.targetAgentId)
        assertTrue(gateway.submissions.isEmpty())
    }

    @Test
    fun `bare UUID without the agent prefix is rejected`() {
        val tool = UseAbilityTool(gateway, tickClock, activity)
        val raw = target.id.toString()

        val response = tool.invoke("SWORD_POWER_STRIKE", raw, toolContext)

        assertEquals(CommandAckKind.REJECTED, response.kind)
        assertEquals("bad_target_agent_id", response.reason)
        assertEquals(raw, response.targetAgentId)
        assertTrue(gateway.submissions.isEmpty())
    }

    @Test
    fun `touches activity registry on every successful invocation`() {
        val tool = UseAbilityTool(gateway, tickClock, activity)

        assertTrue(agent !in activity.staleAgents(clock.instant().minusSeconds(60)))

        tool.invoke("SWORD_POWER_STRIKE", "agent:${target.id}", toolContext)

        assertTrue(agent in activity.staleAgents(clock.instant().plusSeconds(60)))
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
