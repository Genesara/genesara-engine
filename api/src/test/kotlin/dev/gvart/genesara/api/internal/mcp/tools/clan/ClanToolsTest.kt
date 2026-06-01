package dev.gvart.genesara.api.internal.mcp.tools.clan

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityRegistry
import dev.gvart.genesara.api.internal.mcp.tools.CommandAckKind
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.FactionRank
import dev.gvart.genesara.world.AddMemberOutcome
import dev.gvart.genesara.world.Biome
import dev.gvart.genesara.world.Clan
import dev.gvart.genesara.world.ClanId
import dev.gvart.genesara.world.ClanMember
import dev.gvart.genesara.world.ClanMembership
import dev.gvart.genesara.world.ClanRank
import dev.gvart.genesara.world.ClanRegistry
import dev.gvart.genesara.world.Climate
import dev.gvart.genesara.world.CreateClanOutcome
import dev.gvart.genesara.world.Gauge
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.ResourceSpawnRule
import dev.gvart.genesara.world.Terrain
import dev.gvart.genesara.world.WorldCommandGateway
import dev.gvart.genesara.world.commands.ClanCommand
import dev.gvart.genesara.world.commands.WorldCommand
import dev.gvart.genesara.world.internal.balance.BalanceLookup
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

class ClanToolsTest {

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
    fun `create_clan queues a CreateClan command with the trimmed name`() {
        val response = CreateClanTool(gateway, tickClock, activity).invoke("  Ashen Pact  ", toolContext)

        assertEquals(CommandAckKind.QUEUED, response.kind)
        assertEquals(51L, response.appliesAtTick)
        val cmd = assertNotNull(gateway.submissions.single().first as? ClanCommand.CreateClan)
        assertEquals(agent, cmd.agent)
        assertEquals("Ashen Pact", cmd.name)
    }

    @Test
    fun `create_clan rejects a blank name without queuing`() {
        val response = CreateClanTool(gateway, tickClock, activity).invoke("   ", toolContext)

        assertEquals(CommandAckKind.REJECTED, response.kind)
        assertEquals("bad_clan_name", response.reason)
        assertTrue(gateway.submissions.isEmpty())
    }

    @Test
    fun `create_clan rejects a name longer than 64 characters`() {
        val response = CreateClanTool(gateway, tickClock, activity).invoke("x".repeat(65), toolContext)

        assertEquals(CommandAckKind.REJECTED, response.kind)
        assertEquals("bad_clan_name", response.reason)
    }

    @Test
    fun `leave_clan queues a LeaveClan command for the caller`() {
        val response = LeaveClanTool(gateway, tickClock, activity).invoke(toolContext)

        assertEquals(CommandAckKind.QUEUED, response.kind)
        assertEquals(agent, assertNotNull(gateway.submissions.single().first as? ClanCommand.LeaveClan).agent)
    }

    @Test
    fun `dissolve_clan queues a DissolveClan command for the caller`() {
        val response = DissolveClanTool(gateway, tickClock, activity).invoke(toolContext)

        assertEquals(CommandAckKind.QUEUED, response.kind)
        assertEquals(agent, assertNotNull(gateway.submissions.single().first as? ClanCommand.DissolveClan).agent)
    }

    @Test
    fun `transfer_clan_leadership queues with the parsed target`() {
        val response = ClanMembershipTools(gateway, tickClock, activity)
            .transfer("agent:${target.id}", toolContext)

        assertEquals(CommandAckKind.QUEUED, response.kind)
        val cmd = assertNotNull(gateway.submissions.single().first as? ClanCommand.TransferClanLeadership)
        assertEquals(agent, cmd.agent)
        assertEquals(target, cmd.target)
    }

    @Test
    fun `transfer_clan_leadership rejects a malformed target id`() {
        val response = ClanMembershipTools(gateway, tickClock, activity).transfer("not-a-uuid", toolContext)

        assertEquals(CommandAckKind.REJECTED, response.kind)
        assertEquals("bad_target_id", response.reason)
        assertTrue(gateway.submissions.isEmpty())
    }

    @Test
    fun `transfer_clan_leadership rejects transferring to yourself`() {
        val response = ClanMembershipTools(gateway, tickClock, activity)
            .transfer("agent:${agent.id}", toolContext)

        assertEquals(CommandAckKind.REJECTED, response.kind)
        assertEquals("cannot_transfer_to_self", response.reason)
        assertTrue(gateway.submissions.isEmpty())
    }

    @Test
    fun `get_clan_status returns null when the agent is in no clan`() {
        val response = GetClanStatusTool(StubClanRegistry(membership = null), StubBalance, activity).invoke(toolContext)
        assertNull(response.clan)
    }

    @Test
    fun `get_clan_status projects the clan, roster, ranks and cap`() {
        val clanId = ClanId(UUID.randomUUID())
        val clan = Clan(clanId, "Ashen Pact", factionId = null, foundedAtTick = 10L)
        val roster = listOf(
            ClanMember(clanId, agent, ClanRank.ARCHON, FactionRank.PACT, joinedAtTick = 10L),
            ClanMember(clanId, target, ClanRank.SWORN, null, joinedAtTick = 20L),
        )
        val membership = ClanMembership(clan, ClanRank.ARCHON, FactionRank.PACT)

        val response = GetClanStatusTool(StubClanRegistry(membership, roster), StubBalance, activity).invoke(toolContext)

        val view = assertNotNull(response.clan)
        assertEquals(clanId.value, view.clanId)
        assertEquals("Ashen Pact", view.name)
        assertEquals(2, view.memberCount)
        assertEquals(6, view.memberCap)
        assertEquals(ClanRank.ARCHON, view.yourClanRank)
        assertEquals(FactionRank.PACT, view.yourFactionRank)
        assertEquals(listOf(agent.id, target.id), view.members.map { it.agentId })
    }

    @Test
    fun `invite_to_clan queues InviteToClan with the parsed invitee`() {
        val response = InviteToClanTool(gateway, tickClock, activity).invoke("agent:${target.id}", toolContext)

        assertEquals(CommandAckKind.QUEUED, response.kind)
        val cmd = assertNotNull(gateway.submissions.single().first as? ClanCommand.InviteToClan)
        assertEquals(agent, cmd.agent)
        assertEquals(target, cmd.invitee)
    }

    @Test
    fun `invite_to_clan rejects a malformed invitee id`() {
        val response = InviteToClanTool(gateway, tickClock, activity).invoke("not-a-uuid", toolContext)

        assertEquals(CommandAckKind.REJECTED, response.kind)
        assertEquals("bad_invitee_id", response.reason)
        assertTrue(gateway.submissions.isEmpty())
    }

    @Test
    fun `respond_clan_invite queues RespondClanInvite with the parsed id and accept flag`() {
        val inviteId = UUID.randomUUID()
        val response = RespondClanInviteTool(gateway, tickClock, activity).invoke(inviteId.toString(), accept = true, toolContext)

        assertEquals(CommandAckKind.QUEUED, response.kind)
        val cmd = assertNotNull(gateway.submissions.single().first as? ClanCommand.RespondClanInvite)
        assertEquals(inviteId, cmd.inviteId)
        assertTrue(cmd.accept)
    }

    @Test
    fun `respond_clan_invite rejects a malformed invite id`() {
        val response = RespondClanInviteTool(gateway, tickClock, activity).invoke("not-a-uuid", accept = false, toolContext)

        assertEquals(CommandAckKind.REJECTED, response.kind)
        assertEquals("bad_invite_id", response.reason)
        assertTrue(gateway.submissions.isEmpty())
    }

    @Test
    fun `kick_clan_member queues KickClanMember with the parsed target`() {
        val response = ClanMembershipTools(gateway, tickClock, activity).kick("agent:${target.id}", toolContext)

        assertEquals(CommandAckKind.QUEUED, response.kind)
        assertEquals(target, assertNotNull(gateway.submissions.single().first as? ClanCommand.KickClanMember).target)
    }

    @Test
    fun `promote_clan_member queues PromoteClanMember with the parsed target`() {
        val response = ClanMembershipTools(gateway, tickClock, activity).promote(target.id.toString(), toolContext)

        assertEquals(CommandAckKind.QUEUED, response.kind)
        assertEquals(target, assertNotNull(gateway.submissions.single().first as? ClanCommand.PromoteClanMember).target)
    }

    @Test
    fun `demote_clan_member queues DemoteClanMember with the parsed target`() {
        val response = ClanMembershipTools(gateway, tickClock, activity).demote("agent:${target.id}", toolContext)

        assertEquals(CommandAckKind.QUEUED, response.kind)
        assertEquals(target, assertNotNull(gateway.submissions.single().first as? ClanCommand.DemoteClanMember).target)
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

    private class StubClanRegistry(
        private val membership: ClanMembership?,
        private val roster: List<ClanMember> = emptyList(),
    ) : ClanRegistry {
        override fun clanOf(agentId: AgentId): ClanMembership? = membership
        override fun roster(clanId: ClanId): List<ClanMember> = roster
        override fun findClan(clanId: ClanId): Clan? = membership?.clan?.takeIf { it.id == clanId }
        override fun memberCount(clanId: ClanId): Int = roster.size
        override fun createClan(name: String, founder: AgentId, tick: Long): CreateClanOutcome = error("unused")
        override fun addMember(clanId: ClanId, agentId: AgentId, rank: ClanRank, tick: Long): AddMemberOutcome = error("unused")
        override fun changeClanRank(clanId: ClanId, agentId: AgentId, newRank: ClanRank): Boolean = error("unused")
        override fun removeMember(clanId: ClanId, agentId: AgentId): Boolean = error("unused")
        override fun dissolve(clanId: ClanId): List<AgentId> = error("unused")
    }

    private object StubBalance : BalanceLookup {
        override fun moveStaminaCost(biome: Biome, climate: Climate, terrain: Terrain): Int = error("unused")
        override fun staminaRegenPerTick(climate: Climate): Int = error("unused")
        override fun resourceSpawnsFor(terrain: Terrain): List<ResourceSpawnRule> = error("unused")
        override fun harvestStaminaCost(item: ItemId): Int = error("unused")
        override fun harvestYield(item: ItemId): Int = error("unused")
        override fun gaugeDrainPerTick(gauge: Gauge): Int = error("unused")
        override fun gaugeLowThreshold(gauge: Gauge): Int = error("unused")
        override fun starvationDamagePerTick(): Int = error("unused")
        override fun isWaterSource(terrain: Terrain): Boolean = error("unused")
        override fun drinkStaminaCost(): Int = error("unused")
        override fun drinkThirstRefill(): Int = error("unused")
        override fun sleepRegenPerOfflineTick(): Int = error("unused")
        override fun isTraversable(terrain: Terrain): Boolean = error("unused")
    }

    private class MutableTestClock(private var now: Instant) : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
    }
}
