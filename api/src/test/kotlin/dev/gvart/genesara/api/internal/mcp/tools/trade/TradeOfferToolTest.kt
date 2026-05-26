package dev.gvart.genesara.api.internal.mcp.tools.trade

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityRegistry
import dev.gvart.genesara.api.internal.mcp.tools.CommandAckKind
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.WorldCommandGateway
import dev.gvart.genesara.world.commands.EconomyCommand
import dev.gvart.genesara.world.commands.WorldCommand
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.model.ToolContext

class TradeOfferToolTest {

    private val sender = AgentId(UUID.randomUUID())
    private val recipient = AgentId(UUID.randomUUID())
    private val clock = MutableTestClock(Instant.parse("2026-01-01T00:00:00Z"))
    private val activity = AgentActivityRegistry(clock)
    private val gateway = RecordingGateway()
    private val tickClock = StubTickClock(currentTick = 10L)
    private val toolContext = ToolContext(emptyMap())

    @BeforeEach fun setUp() = AgentContextHolder.set(sender)
    @AfterEach fun tearDown() = AgentContextHolder.clear()

    @Test
    fun `accepts wire-prefixed agent-colon form for recipientId`() {
        val tool = TradeOfferTool(gateway, tickClock, activity)

        val response = tool.invoke(
            recipientId = "agent:${recipient.id}",
            offer = mapOf("WOOD" to 5),
            request = emptyMap(),
            offerInstances = null,
            requestInstances = null,
            toolContext = toolContext,
        )

        assertEquals(CommandAckKind.QUEUED, response.kind)
        val (cmd, _) = gateway.submissions.single()
        val offer = assertNotNull(cmd as? EconomyCommand.TradeOffer)
        assertEquals(recipient, offer.recipient)
    }

    @Test
    fun `accepts bare UUID form for recipientId`() {
        val tool = TradeOfferTool(gateway, tickClock, activity)

        val response = tool.invoke(
            recipientId = recipient.id.toString(),
            offer = mapOf("WOOD" to 5),
            request = emptyMap(),
            offerInstances = null,
            requestInstances = null,
            toolContext = toolContext,
        )

        assertEquals(CommandAckKind.QUEUED, response.kind)
        val (cmd, _) = gateway.submissions.single()
        val offer = assertNotNull(cmd as? EconomyCommand.TradeOffer)
        assertEquals(recipient, offer.recipient)
    }

    @Test
    fun `rejects a malformed recipientId that is not a UUID`() {
        val tool = TradeOfferTool(gateway, tickClock, activity)

        val response = tool.invoke(
            recipientId = "not-a-uuid",
            offer = mapOf("WOOD" to 5),
            request = emptyMap(),
            offerInstances = null,
            requestInstances = null,
            toolContext = toolContext,
        )

        assertEquals(CommandAckKind.REJECTED, response.kind)
        assertEquals("bad_recipient_id", response.reason)
        assertTrue(gateway.submissions.isEmpty())
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
