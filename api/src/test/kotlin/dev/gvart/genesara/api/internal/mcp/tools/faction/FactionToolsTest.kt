package dev.gvart.genesara.api.internal.mcp.tools.faction

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityRegistry
import dev.gvart.genesara.api.internal.mcp.tools.CommandAckKind
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.ClanId
import dev.gvart.genesara.world.WorldCommandGateway
import dev.gvart.genesara.world.commands.FactionCommand
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

class FactionToolsTest {

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
    fun `create_faction queues CreateFaction with the trimmed name`() {
        val response = CreateFactionTool(gateway, tickClock, activity).invoke("  Concord  ", toolContext)
        assertEquals(CommandAckKind.QUEUED, response.kind)
        assertEquals("Concord", assertNotNull(gateway.submissions.single().first as? FactionCommand.CreateFaction).name)
    }

    @Test
    fun `create_faction rejects a blank name`() {
        val response = CreateFactionTool(gateway, tickClock, activity).invoke("  ", toolContext)
        assertEquals(CommandAckKind.REJECTED, response.kind)
        assertEquals("bad_faction_name", response.reason)
        assertTrue(gateway.submissions.isEmpty())
    }

    @Test
    fun `invite_clan_to_faction queues InviteClanToFaction with the parsed clan id`() {
        val clanId = UUID.randomUUID()
        val response = InviteClanToFactionTool(gateway, tickClock, activity).invoke(clanId.toString(), toolContext)
        assertEquals(CommandAckKind.QUEUED, response.kind)
        assertEquals(ClanId(clanId), assertNotNull(gateway.submissions.single().first as? FactionCommand.InviteClanToFaction).targetClanId)
    }

    @Test
    fun `invite_clan_to_faction rejects a malformed clan id`() {
        val response = InviteClanToFactionTool(gateway, tickClock, activity).invoke("not-a-uuid", toolContext)
        assertEquals(CommandAckKind.REJECTED, response.kind)
        assertEquals("bad_clan_id", response.reason)
        assertTrue(gateway.submissions.isEmpty())
    }

    @Test
    fun `respond_faction_invite queues with the parsed id and accept flag`() {
        val inviteId = UUID.randomUUID()
        val response = RespondFactionInviteTool(gateway, tickClock, activity).invoke(inviteId.toString(), accept = true, toolContext)
        assertEquals(CommandAckKind.QUEUED, response.kind)
        val cmd = assertNotNull(gateway.submissions.single().first as? FactionCommand.RespondFactionInvite)
        assertEquals(inviteId, cmd.inviteId)
        assertTrue(cmd.accept)
    }

    @Test
    fun `leave_faction queues LeaveFaction for the caller`() {
        val response = LeaveFactionTool(gateway, tickClock, activity).invoke(toolContext)
        assertEquals(CommandAckKind.QUEUED, response.kind)
        assertEquals(agent, assertNotNull(gateway.submissions.single().first as? FactionCommand.LeaveFaction).agent)
    }

    @Test
    fun `promote_faction_member queues PromoteFactionMember with the parsed target`() {
        val response = PromoteFactionMemberTool(gateway, tickClock, activity).invoke("agent:${target.id}", toolContext)
        assertEquals(CommandAckKind.QUEUED, response.kind)
        assertEquals(target, assertNotNull(gateway.submissions.single().first as? FactionCommand.PromoteFactionMember).target)
    }

    @Test
    fun `demote_faction_member queues DemoteFactionMember with the parsed target`() {
        val response = DemoteFactionMemberTool(gateway, tickClock, activity).invoke(target.id.toString(), toolContext)
        assertEquals(CommandAckKind.QUEUED, response.kind)
        assertEquals(target, assertNotNull(gateway.submissions.single().first as? FactionCommand.DemoteFactionMember).target)
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
