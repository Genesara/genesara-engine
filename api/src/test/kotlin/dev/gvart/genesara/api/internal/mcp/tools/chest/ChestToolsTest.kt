package dev.gvart.genesara.api.internal.mcp.tools.chest

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityRegistry
import dev.gvart.genesara.api.internal.mcp.tools.CommandAckKind
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

    @Test
    fun `deposit queues a DepositToChest command at the next tick`() {
        val tool = DepositToChestTool(gateway, tickClock, activity)

        val response = tool.invoke(
            chestId = chest.toString(),
            itemId = "WOOD",
            quantity = 5,
            toolContext = toolContext,
        )

        assertEquals(CommandAckKind.QUEUED, response.kind)
        assertEquals(chest.toString(), response.chestId)
        assertEquals("WOOD", response.itemId)
        assertEquals(5, response.quantity)
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
    fun `withdraw queues a WithdrawFromChest command at the next tick`() {
        val tool = WithdrawFromChestTool(gateway, tickClock, activity)

        val response = tool.invoke(
            chestId = chest.toString(),
            itemId = "STONE",
            quantity = 3,
            toolContext = toolContext,
        )

        assertEquals(CommandAckKind.QUEUED, response.kind)
        assertEquals(chest.toString(), response.chestId)
        assertEquals("STONE", response.itemId)
        assertEquals(3, response.quantity)
        val (cmd, _) = gateway.submissions.single()
        val withdraw = assertNotNull(cmd as? WorldCommand.WithdrawFromChest)
        assertEquals(agent, withdraw.agent)
        assertEquals(chest, withdraw.chestId)
        assertEquals(ItemId("STONE"), withdraw.item)
        assertEquals(3, withdraw.quantity)
    }

    @Test
    fun `non-positive quantity is forwarded to the reducer (not rejected at the tool boundary)`() {
        val tool = DepositToChestTool(gateway, tickClock, activity)

        tool.invoke(chestId = chest.toString(), itemId = "WOOD", quantity = 0, toolContext = toolContext)

        val cmd = assertNotNull(gateway.submissions.single().first as? WorldCommand.DepositToChest)
        assertEquals(0, cmd.quantity)
    }

    @Test
    fun `deposit rejects malformed chestId without queueing`() {
        val tool = DepositToChestTool(gateway, tickClock, activity)

        val response = tool.invoke(chestId = "not-a-uuid", itemId = "WOOD", quantity = 1, toolContext = toolContext)

        assertEquals(CommandAckKind.REJECTED, response.kind)
        assertEquals("not-a-uuid", response.chestId)
        assertEquals("bad_chest_id", response.reason)
        assertNull(response.commandId)
        assertTrue(gateway.submissions.isEmpty())
    }

    @Test
    fun `withdraw rejects malformed chestId without queueing`() {
        val tool = WithdrawFromChestTool(gateway, tickClock, activity)

        val response = tool.invoke(chestId = "BERRY", itemId = "WOOD", quantity = 1, toolContext = toolContext)

        assertEquals(CommandAckKind.REJECTED, response.kind)
        assertEquals("BERRY", response.chestId)
        assertEquals("bad_chest_id", response.reason)
        assertTrue(gateway.submissions.isEmpty())
    }

    @Test
    fun `both tools touch activity registry on success`() {
        val deposit = DepositToChestTool(gateway, tickClock, activity)
        val withdraw = WithdrawFromChestTool(gateway, tickClock, activity)

        deposit.invoke(chest.toString(), "WOOD", 1, toolContext)
        AgentContextHolder.set(agent)
        withdraw.invoke(chest.toString(), "WOOD", 1, toolContext)

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
