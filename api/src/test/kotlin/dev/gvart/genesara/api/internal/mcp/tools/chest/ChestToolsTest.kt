package dev.gvart.genesara.api.internal.mcp.tools.chest

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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChestToolsTest {

    private val agent = AgentId(UUID.randomUUID())
    private val chest = UUID.randomUUID()
    private val clock = MutableTestClock(Instant.parse("2026-01-01T00:00:00Z"))
    private val activity = AgentActivityRegistry(clock)
    private val gateway = RecordingGateway()
    private val tickClock = StubTickClock(currentTick = 50L)
    private val toolContext = ToolContext(emptyMap())

    @BeforeEach fun setUp() = AgentContextHolder.set(agent)
    @AfterEach fun tearDown() = AgentContextHolder.clear()

    // ─────────────────────── Deposit ───────────────────────

    @Test
    fun `deposit queues a DepositToChest command at the next tick`() {
        val tool = DepositToChestTool(gateway, tickClock, activity)

        val response = tool.invoke(
            ChestTransferRequest(chestId = chest.toString(), itemId = "WOOD", quantity = 5),
            toolContext,
        )

        assertEquals("queued", response.kind)
        assertEquals(51L, response.appliesAtTick)
        val (cmd, appliesAt) = gateway.submissions.single()
        val deposit = assertNotNull(cmd as? WorldCommand.DepositToChest)
        assertEquals(agent, deposit.agent)
        assertEquals(chest, deposit.chestId)
        assertEquals(ItemId("WOOD"), deposit.item)
        assertEquals(5, deposit.quantity)
        assertEquals(51L, appliesAt)
        assertEquals(deposit.commandId, response.commandId)
    }

    @Test
    fun `deposit rejects malformed UUID at the boundary`() {
        val tool = DepositToChestTool(gateway, tickClock, activity)

        val response = tool.invoke(
            ChestTransferRequest(chestId = "not-a-uuid", itemId = "WOOD", quantity = 5),
            toolContext,
        )

        assertEquals("error", response.kind)
        assertNotNull(response.error)
        assertNull(response.commandId)
        assertTrue(gateway.submissions.isEmpty())
    }

    @Test
    fun `deposit rejects blank itemId at the boundary`() {
        val tool = DepositToChestTool(gateway, tickClock, activity)

        val response = tool.invoke(
            ChestTransferRequest(chestId = chest.toString(), itemId = "   ", quantity = 5),
            toolContext,
        )

        assertEquals("error", response.kind)
        assertTrue(gateway.submissions.isEmpty())
    }

    @Test
    fun `deposit rejects non-positive quantity at the boundary`() {
        val tool = DepositToChestTool(gateway, tickClock, activity)

        listOf(0, -1).forEach { qty ->
            val response = tool.invoke(
                ChestTransferRequest(chestId = chest.toString(), itemId = "WOOD", quantity = qty),
                toolContext,
            )
            assertEquals("error", response.kind, "quantity=$qty")
        }
        assertTrue(gateway.submissions.isEmpty())
    }

    // ─────────────────────── Withdraw ───────────────────────

    @Test
    fun `withdraw queues a WithdrawFromChest command at the next tick`() {
        val tool = WithdrawFromChestTool(gateway, tickClock, activity)

        val response = tool.invoke(
            ChestTransferRequest(chestId = chest.toString(), itemId = "STONE", quantity = 3),
            toolContext,
        )

        assertEquals("queued", response.kind)
        val (cmd, _) = gateway.submissions.single()
        val withdraw = assertNotNull(cmd as? WorldCommand.WithdrawFromChest)
        assertEquals(agent, withdraw.agent)
        assertEquals(chest, withdraw.chestId)
        assertEquals(ItemId("STONE"), withdraw.item)
        assertEquals(3, withdraw.quantity)
    }

    @Test
    fun `withdraw rejects malformed UUID at the boundary`() {
        val tool = WithdrawFromChestTool(gateway, tickClock, activity)

        val response = tool.invoke(
            ChestTransferRequest(chestId = "garbage", itemId = "WOOD", quantity = 1),
            toolContext,
        )

        assertEquals("error", response.kind)
        assertTrue(gateway.submissions.isEmpty())
    }

    @Test
    fun `withdraw rejects non-positive quantity`() {
        val tool = WithdrawFromChestTool(gateway, tickClock, activity)

        val response = tool.invoke(
            ChestTransferRequest(chestId = chest.toString(), itemId = "WOOD", quantity = 0),
            toolContext,
        )

        assertEquals("error", response.kind)
        assertTrue(gateway.submissions.isEmpty())
    }

    // ─────────────────────── Activity tracking ───────────────────────

    @Test
    fun `both tools touch activity registry on success`() {
        val deposit = DepositToChestTool(gateway, tickClock, activity)
        val withdraw = WithdrawFromChestTool(gateway, tickClock, activity)

        deposit.invoke(ChestTransferRequest(chest.toString(), "WOOD", 1), toolContext)
        AgentContextHolder.set(agent)
        withdraw.invoke(ChestTransferRequest(chest.toString(), "WOOD", 1), toolContext)

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
