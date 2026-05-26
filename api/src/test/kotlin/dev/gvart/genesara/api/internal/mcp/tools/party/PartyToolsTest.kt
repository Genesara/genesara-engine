package dev.gvart.genesara.api.internal.mcp.tools.party

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityRegistry
import dev.gvart.genesara.api.internal.mcp.tools.CommandAckKind
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.Party
import dev.gvart.genesara.world.PartyId
import dev.gvart.genesara.world.PartyMember
import dev.gvart.genesara.world.WorldCommandGateway
import dev.gvart.genesara.world.commands.SocialCommand
import dev.gvart.genesara.world.commands.WorldCommand
import dev.gvart.genesara.world.internal.worldstate.views.PartyReadView
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.model.ToolContext

class PartyToolsTest {

    private val leader = AgentId(UUID.randomUUID())
    private val alice = AgentId(UUID.randomUUID())
    private val bob = AgentId(UUID.randomUUID())
    private val clock = MutableTestClock(Instant.parse("2026-01-01T00:00:00Z"))
    private val activity = AgentActivityRegistry(clock)
    private val gateway = RecordingGateway()
    private val tickClock = StubTickClock(currentTick = 50L)
    private val toolContext = ToolContext(emptyMap())

    @BeforeEach fun setUp() = AgentContextHolder.set(leader)
    @AfterEach fun tearDown() = AgentContextHolder.clear()

    // ─────────────────────── party_invite ───────────────────────

    @Test
    fun `party_invite queues a SocialCommand with parsed agent ids`() {
        val tool = PartyInviteTool(gateway, tickClock, activity)
        val invitees = "agent:${alice.id},agent:${bob.id}"

        val response = tool.invoke(invitees, toolContext)

        assertEquals(CommandAckKind.QUEUED, response.kind)
        assertEquals(51L, response.appliesAtTick)
        val (cmd, _) = gateway.submissions.single()
        val invite = assertNotNull(cmd as? SocialCommand.PartyInvite)
        assertEquals(leader, invite.agent)
        assertEquals(listOf(alice, bob), invite.invitees)
    }

    @Test
    fun `party_invite rejects empty invitees`() {
        val tool = PartyInviteTool(gateway, tickClock, activity)

        val response = tool.invoke("", toolContext)

        assertEquals(CommandAckKind.REJECTED, response.kind)
        assertEquals("empty_invitees", response.reason)
        assertTrue(gateway.submissions.isEmpty())
    }

    @Test
    fun `party_invite accepts bare UUID form`() {
        val tool = PartyInviteTool(gateway, tickClock, activity)

        val response = tool.invoke(alice.id.toString(), toolContext)

        assertEquals(CommandAckKind.QUEUED, response.kind)
        val (cmd, _) = gateway.submissions.single()
        val invite = cmd as SocialCommand.PartyInvite
        assertEquals(listOf(alice), invite.invitees)
    }

    @Test
    fun `party_invite rejects a malformed id that is not a UUID`() {
        val tool = PartyInviteTool(gateway, tickClock, activity)

        val response = tool.invoke("not-a-uuid", toolContext)

        assertEquals(CommandAckKind.REJECTED, response.kind)
        assertEquals("bad_invitee_id", response.reason)
    }

    @Test
    fun `party_invite skips empty entries between commas`() {
        val tool = PartyInviteTool(gateway, tickClock, activity)
        val invitees = "agent:${alice.id},,agent:${bob.id}"

        val response = tool.invoke(invitees, toolContext)

        assertEquals(CommandAckKind.QUEUED, response.kind)
        val (cmd, _) = gateway.submissions.single()
        val invite = cmd as SocialCommand.PartyInvite
        assertEquals(listOf(alice, bob), invite.invitees)
    }

    // ─────────────────────── party_respond ───────────────────────

    @Test
    fun `party_respond queues with parsed invite id and accept flag`() {
        val tool = PartyRespondTool(gateway, tickClock, activity)
        val inviteId = UUID.randomUUID()

        val response = tool.invoke(inviteId.toString(), accept = true, toolContext)

        assertEquals(CommandAckKind.QUEUED, response.kind)
        val (cmd, _) = gateway.submissions.single()
        val respond = assertNotNull(cmd as? SocialCommand.PartyRespond)
        assertEquals(inviteId, respond.inviteId)
        assertTrue(respond.accept)
    }

    @Test
    fun `party_respond rejects a malformed invite id`() {
        val tool = PartyRespondTool(gateway, tickClock, activity)

        val response = tool.invoke("not-a-uuid", accept = false, toolContext)

        assertEquals(CommandAckKind.REJECTED, response.kind)
        assertEquals("bad_invite_id", response.reason)
        assertTrue(gateway.submissions.isEmpty())
    }

    // ─────────────────────── leave_party ───────────────────────

    @Test
    fun `leave_party queues a SocialCommand for the calling agent`() {
        val tool = LeavePartyTool(gateway, tickClock, activity)

        val response = tool.invoke(toolContext)

        assertEquals(CommandAckKind.QUEUED, response.kind)
        val (cmd, _) = gateway.submissions.single()
        val leave = assertNotNull(cmd as? SocialCommand.LeaveParty)
        assertEquals(leader, leave.agent)
    }

    // ─────────────────────── kick_member ───────────────────────

    @Test
    fun `kick_member queues a SocialCommand with parsed target`() {
        val tool = KickMemberTool(gateway, tickClock, activity)
        val wire = "agent:${alice.id}"

        val response = tool.invoke(wire, toolContext)

        assertEquals(CommandAckKind.QUEUED, response.kind)
        val (cmd, _) = gateway.submissions.single()
        val kick = assertNotNull(cmd as? SocialCommand.KickPartyMember)
        assertEquals(leader, kick.agent)
        assertEquals(alice, kick.target)
    }

    @Test
    fun `kick_member accepts bare UUID form`() {
        val tool = KickMemberTool(gateway, tickClock, activity)

        val response = tool.invoke(alice.id.toString(), toolContext)

        assertEquals(CommandAckKind.QUEUED, response.kind)
        val (cmd, _) = gateway.submissions.single()
        val kick = assertNotNull(cmd as? SocialCommand.KickPartyMember)
        assertEquals(alice, kick.target)
    }

    @Test
    fun `kick_member rejects a malformed id that is not a UUID`() {
        val tool = KickMemberTool(gateway, tickClock, activity)

        val response = tool.invoke("not-a-uuid", toolContext)

        assertEquals(CommandAckKind.REJECTED, response.kind)
        assertEquals("bad_target_id", response.reason)
    }

    // ─────────────────────── get_party ───────────────────────

    @Test
    fun `get_party returns null when agent has no party`() {
        val tool = GetPartyTool(StubPartyReadView(null), activity)

        val response = tool.invoke(toolContext)

        assertNull(response.party)
    }

    @Test
    fun `get_party projects the current party with members ordered by join tick`() {
        val partyId = PartyId(UUID.randomUUID())
        val party = Party(
            partyId = partyId,
            leaderId = leader,
            members = listOf(
                PartyMember(leader, joinedAtTick = 10L),
                PartyMember(alice, joinedAtTick = 20L),
            ),
            formedAtTick = 10L,
        )
        val tool = GetPartyTool(StubPartyReadView(party), activity)

        val response = tool.invoke(toolContext)

        val view = assertNotNull(response.party)
        assertEquals(partyId.value, view.partyId)
        assertEquals(leader.id, view.leader)
        assertEquals(10L, view.formedAtTick)
        assertEquals(listOf(leader.id, alice.id), view.members.map { it.agentId })
        assertEquals(listOf(10L, 20L), view.members.map { it.joinedAtTick })
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

    private class StubPartyReadView(private val party: Party?) : PartyReadView {
        override fun partyOf(agentId: AgentId): Party? = party
        override fun partyIdOf(agentId: AgentId): PartyId? = party?.partyId
        override fun find(partyId: PartyId): Party? = party?.takeIf { it.partyId == partyId }
    }

    private class MutableTestClock(private var now: Instant) : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
    }
}
