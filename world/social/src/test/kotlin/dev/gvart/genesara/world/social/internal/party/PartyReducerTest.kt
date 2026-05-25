package dev.gvart.genesara.world.social.internal.party

import dev.gvart.genesara.account.PlayerId
import dev.gvart.genesara.player.Agent
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.world.Biome
import dev.gvart.genesara.world.Building
import dev.gvart.genesara.world.BuildingCategoryHint
import dev.gvart.genesara.world.BuildingsLookup
import dev.gvart.genesara.world.Climate
import dev.gvart.genesara.world.Node
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.Party
import dev.gvart.genesara.world.PartyId
import dev.gvart.genesara.world.PartyInvite
import dev.gvart.genesara.world.PartyInviteId
import dev.gvart.genesara.world.PartyInviteStore
import dev.gvart.genesara.world.PartyMember
import dev.gvart.genesara.world.PartyStore
import dev.gvart.genesara.world.Region
import dev.gvart.genesara.world.RegionId
import dev.gvart.genesara.world.Terrain
import dev.gvart.genesara.world.Vec3
import dev.gvart.genesara.world.VisibleNodes
import dev.gvart.genesara.world.WorldId
import dev.gvart.genesara.world.WorldRejection
import dev.gvart.genesara.world.commands.SocialCommand
import dev.gvart.genesara.world.events.SocialEvent
import dev.gvart.genesara.world.internal.balance.BalanceLookup
import dev.gvart.genesara.world.internal.worldstate.slices.CoreSlice
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class PartyReducerTest {

    private companion object {
        const val TICK_INTERVAL_SECONDS: Long = 5L
    }

    private val leader = AgentId(UUID.randomUUID())
    private val alice = AgentId(UUID.randomUUID())
    private val bob = AgentId(UUID.randomUUID())
    private val carol = AgentId(UUID.randomUUID())
    private val regionId = RegionId(1L)
    private val nodeId = NodeId(1L)
    private val farNodeId = NodeId(99L)
    private val region = Region(
        id = regionId, worldId = WorldId(1L), sphereIndex = 0,
        biome = Biome.PLAINS, climate = Climate.OCEANIC,
        centroid = Vec3(0.0, 0.0, 1.0), faceVertices = emptyList(), neighbors = emptySet(),
    )
    private val balance = TestBalance()

    private fun coreWith(positions: Map<AgentId, NodeId>): CoreSlice = CoreSlice(
        regions = mapOf(regionId to region),
        nodes = mapOf(
            nodeId to Node(nodeId, regionId, q = 0, r = 0, terrain = Terrain.FOREST, adjacency = emptySet()),
            farNodeId to Node(farNodeId, regionId, q = 99, r = 99, terrain = Terrain.FOREST, adjacency = emptySet()),
        ),
        positions = positions,
    )

    private fun freshFixture(visibility: Set<NodeId> = setOf(nodeId)): Fixture {
        return Fixture(
            partyStore = InMemoryPartyStore(),
            invites = InMemoryPartyInviteStore(),
            visibleNodes = StubVisibleNodes(visibility),
            agents = StubAgents(setOf(leader, alice, bob, carol)),
        )
    }

    // ─────────────────────── party_invite ───────────────────────

    @Test
    fun `invite from solo agent persists invite and emits PartyInviteReceived`() {
        val fx = freshFixture()
        val core = coreWith(mapOf(leader to nodeId, alice to nodeId))

        val out = assertNotNull(
            reducePartyInvite(
                core, SocialCommand.PartyInvite(leader, listOf(alice)),
                balance, fx.partyStore, fx.invites, fx.visibleNodes, fx.agents, NoBuildings,
                tickIntervalSeconds = TICK_INTERVAL_SECONDS, tick = 10L,
            ).getOrNull()
        )

        val event = assertIs<SocialEvent.PartyInviteReceived>(out.events.single())
        assertEquals(alice, event.invitee)
        assertEquals(leader, event.inviter)
        assertEquals(setOf(alice), event.listeners)
        val expectedTtlTicks = (balance.partyInviteTtlSeconds() + TICK_INTERVAL_SECONDS - 1) / TICK_INTERVAL_SECONDS
        assertEquals(10L + expectedTtlTicks, event.expiresAtTick)
        assertEquals(1, fx.invites.findByInviter(leader).size)
    }

    @Test
    fun `invite rejects self-invite`() {
        val fx = freshFixture()
        val core = coreWith(mapOf(leader to nodeId))

        val rejection = reducePartyInvite(
            core, SocialCommand.PartyInvite(leader, listOf(leader)),
            balance, fx.partyStore, fx.invites, fx.visibleNodes, fx.agents, NoBuildings,
            tickIntervalSeconds = TICK_INTERVAL_SECONDS, tick = 1L,
        ).leftOrNull()

        assertEquals(WorldRejection.CannotPartyWithSelf(leader), rejection)
    }

    @Test
    fun `invite rejects when inviter is not spawned`() {
        val fx = freshFixture()
        val core = coreWith(mapOf(alice to nodeId))

        val rejection = reducePartyInvite(
            core, SocialCommand.PartyInvite(leader, listOf(alice)),
            balance, fx.partyStore, fx.invites, fx.visibleNodes, fx.agents, NoBuildings,
            TICK_INTERVAL_SECONDS, 1L,
        ).leftOrNull()

        assertEquals(WorldRejection.NotInWorld(leader), rejection)
    }

    @Test
    fun `invite rejects when invitee is not spawned`() {
        val fx = freshFixture()
        val core = coreWith(mapOf(leader to nodeId))

        val rejection = reducePartyInvite(
            core, SocialCommand.PartyInvite(leader, listOf(alice)),
            balance, fx.partyStore, fx.invites, fx.visibleNodes, fx.agents, NoBuildings,
            TICK_INTERVAL_SECONDS, 1L,
        ).leftOrNull()

        assertEquals(WorldRejection.InviteeNotInWorld(leader, alice), rejection)
    }

    @Test
    fun `invite rejects when invitee is outside the visible set`() {
        val fx = freshFixture(visibility = setOf(nodeId))
        val core = coreWith(mapOf(leader to nodeId, alice to farNodeId))

        val rejection = assertIs<WorldRejection.InviteeNotInSight>(
            reducePartyInvite(
                core, SocialCommand.PartyInvite(leader, listOf(alice)),
                balance, fx.partyStore, fx.invites, fx.visibleNodes, fx.agents, NoBuildings,
            TICK_INTERVAL_SECONDS, 1L,
            ).leftOrNull()
        )

        assertEquals(alice, rejection.invitee)
        assertEquals(nodeId, rejection.inviterAt)
        assertEquals(farNodeId, rejection.inviteeAt)
    }

    @Test
    fun `invite rejects when invitee is already in a party`() {
        val fx = freshFixture()
        val core = coreWith(mapOf(leader to nodeId, alice to nodeId))
        fx.partyStore.create(
            Party(
                partyId = PartyId(UUID.randomUUID()),
                leaderId = alice,
                members = listOf(PartyMember(alice, 0L)),
                formedAtTick = 0L,
            )
        )

        val rejection = reducePartyInvite(
            core, SocialCommand.PartyInvite(leader, listOf(alice)),
            balance, fx.partyStore, fx.invites, fx.visibleNodes, fx.agents, NoBuildings,
            TICK_INTERVAL_SECONDS, 1L,
        ).leftOrNull()

        assertEquals(WorldRejection.InviteeAlreadyInParty(leader, alice), rejection)
    }

    @Test
    fun `invite from non-leader is rejected when inviter is already in a party`() {
        val fx = freshFixture()
        val core = coreWith(mapOf(leader to nodeId, alice to nodeId, bob to nodeId))
        val partyId = PartyId(UUID.randomUUID())
        fx.partyStore.create(
            Party(
                partyId = partyId,
                leaderId = leader,
                members = listOf(PartyMember(leader, 0L), PartyMember(alice, 1L)),
                formedAtTick = 0L,
            )
        )

        val rejection = assertIs<WorldRejection.NotPartyLeader>(
            reducePartyInvite(
                core, SocialCommand.PartyInvite(alice, listOf(bob)),
                balance, fx.partyStore, fx.invites, fx.visibleNodes, fx.agents, NoBuildings,
            TICK_INTERVAL_SECONDS, 1L,
            ).leftOrNull()
        )
        assertEquals(partyId.value, rejection.partyId)
    }

    @Test
    fun `invite enforces cap counting current members and pending invites`() {
        val fx = freshFixture()
        val core = coreWith(mapOf(leader to nodeId, alice to nodeId, bob to nodeId, carol to nodeId))
        // Pre-seed a 5-member party so a single new invite would push to 6 (OK)
        // but a 2-batch would push to 7 (rejected).
        val members = (1..5).map { idx -> PartyMember(AgentId(UUID.randomUUID()), idx.toLong()) }
        val partyId = PartyId(UUID.randomUUID())
        fx.partyStore.create(
            Party(partyId = partyId, leaderId = leader, members = listOf(PartyMember(leader, 0L)) + members.drop(1), formedAtTick = 0L)
        )

        val rejection = assertIs<WorldRejection.PartyCapacityExceeded>(
            reducePartyInvite(
                core, SocialCommand.PartyInvite(leader, listOf(alice, bob)),
                balance, fx.partyStore, fx.invites, fx.visibleNodes, fx.agents, NoBuildings,
            TICK_INTERVAL_SECONDS, 1L,
            ).leftOrNull()
        )
        assertEquals(5, rejection.currentMembers)
        assertEquals(0, rejection.pendingInvites)
        assertEquals(2, rejection.requested)
        assertEquals(balance.partyMaxSize(), rejection.cap)
    }

    @Test
    fun `re-issuing an invite to the same invitee refreshes TTL without a new event`() {
        val fx = freshFixture()
        val core = coreWith(mapOf(leader to nodeId, alice to nodeId))

        val first = reducePartyInvite(
            core, SocialCommand.PartyInvite(leader, listOf(alice)),
            balance, fx.partyStore, fx.invites, fx.visibleNodes, fx.agents, NoBuildings,
            TICK_INTERVAL_SECONDS, 1L,
        ).getOrNull()!!
        val firstInvite = fx.invites.findByInviter(leader).single()
        assertEquals(1, first.events.size)

        val second = reducePartyInvite(
            core, SocialCommand.PartyInvite(leader, listOf(alice)),
            balance, fx.partyStore, fx.invites, fx.visibleNodes, fx.agents, NoBuildings,
            TICK_INTERVAL_SECONDS, 2L,
        ).getOrNull()!!

        assertTrue(second.events.isEmpty(), "duplicate invite should not emit a new event")
        assertEquals(firstInvite.inviteId, fx.invites.findByInviter(leader).single().inviteId)
    }

    // ─────────────────────── party_respond ───────────────────────

    @Test
    fun `accept on solo inviter creates party with leader + invitee and emits PartyJoined`() {
        val fx = freshFixture()
        val core = coreWith(mapOf(leader to nodeId, alice to nodeId))
        val invite = fx.seedInvite(leader, alice)

        val out = assertNotNull(
            reducePartyRespond(
                core, SocialCommand.PartyRespond(alice, invite.inviteId.value, accept = true),
                balance, fx.partyStore, fx.invites, tick = 50L,
            ).getOrNull()
        )

        val event = assertIs<SocialEvent.PartyJoined>(out.events.single())
        assertEquals(leader, event.leader)
        assertEquals(alice, event.joiner)
        assertEquals(setOf(leader, alice), event.listeners)
        assertEquals(2, event.members.size)
        assertNull(fx.invites.find(invite.inviteId), "invite should be consumed")
        assertNotNull(fx.partyStore.findByAgent(leader))
        assertNotNull(fx.partyStore.findByAgent(alice))
    }

    @Test
    fun `accept on existing party appends the invitee and emits joined to the full roster`() {
        val fx = freshFixture()
        val core = coreWith(mapOf(leader to nodeId, alice to nodeId, bob to nodeId))
        val partyId = PartyId(UUID.randomUUID())
        fx.partyStore.create(
            Party(
                partyId = partyId,
                leaderId = leader,
                members = listOf(PartyMember(leader, 0L), PartyMember(alice, 1L)),
                formedAtTick = 0L,
            )
        )
        val invite = fx.seedInvite(leader, bob)

        val out = reducePartyRespond(
            core, SocialCommand.PartyRespond(bob, invite.inviteId.value, true),
            balance, fx.partyStore, fx.invites, 100L,
        ).getOrNull()!!

        val event = assertIs<SocialEvent.PartyJoined>(out.events.single())
        assertEquals(setOf(leader, alice, bob), event.listeners)
        assertEquals(3, event.members.size)
        assertEquals(partyId, fx.partyStore.findByAgent(bob)!!.partyId)
    }

    @Test
    fun `decline emits PartyInviteDeclined and consumes the invite`() {
        val fx = freshFixture()
        val core = coreWith(mapOf(leader to nodeId, alice to nodeId))
        val invite = fx.seedInvite(leader, alice)

        val out = reducePartyRespond(
            core, SocialCommand.PartyRespond(alice, invite.inviteId.value, accept = false),
            balance, fx.partyStore, fx.invites, 5L,
        ).getOrNull()!!

        val event = assertIs<SocialEvent.PartyInviteDeclined>(out.events.single())
        assertEquals(setOf(leader), event.listeners)
        assertNull(fx.invites.find(invite.inviteId))
        assertNull(fx.partyStore.findByAgent(leader))
    }

    @Test
    fun `accept of unknown invite is rejected as PartyInviteNotFound`() {
        val fx = freshFixture()
        val core = coreWith(mapOf(alice to nodeId))
        val ghost = UUID.randomUUID()

        val rejection = reducePartyRespond(
            core, SocialCommand.PartyRespond(alice, ghost, true), balance,
            fx.partyStore, fx.invites, 5L,
        ).leftOrNull()

        assertEquals(WorldRejection.PartyInviteNotFound(alice, ghost), rejection)
    }

    @Test
    fun `accept by non-invitee is rejected`() {
        val fx = freshFixture()
        val core = coreWith(mapOf(leader to nodeId, alice to nodeId, bob to nodeId))
        val invite = fx.seedInvite(leader, alice)

        val rejection = reducePartyRespond(
            core, SocialCommand.PartyRespond(bob, invite.inviteId.value, true),
            balance, fx.partyStore, fx.invites, 5L,
        ).leftOrNull()

        assertEquals(WorldRejection.NotPartyInvitee(bob, invite.inviteId.value), rejection)
    }

    @Test
    fun `accept while already in a party voids the invite`() {
        val fx = freshFixture()
        val core = coreWith(mapOf(leader to nodeId, alice to nodeId))
        // Alice is already in another party.
        fx.partyStore.create(
            Party(
                partyId = PartyId(UUID.randomUUID()),
                leaderId = alice,
                members = listOf(PartyMember(alice, 0L)),
                formedAtTick = 0L,
            )
        )
        val invite = fx.seedInvite(leader, alice)

        val rejection = assertIs<WorldRejection.PartyInviteVoid>(
            reducePartyRespond(
                core, SocialCommand.PartyRespond(alice, invite.inviteId.value, true),
                balance, fx.partyStore, fx.invites, 5L,
            ).leftOrNull()
        )
        assertEquals(WorldRejection.PartyInviteVoid.PartyInviteVoidReason.INVITEE_ALREADY_IN_PARTY, rejection.reason)
        assertNull(fx.invites.find(invite.inviteId), "void invite should be consumed")
    }

    @Test
    fun `accept when inviter is no longer leader voids the invite`() {
        val fx = freshFixture()
        val core = coreWith(mapOf(leader to nodeId, alice to nodeId, bob to nodeId))
        // Leader is now a non-leader in a different party.
        fx.partyStore.create(
            Party(
                partyId = PartyId(UUID.randomUUID()),
                leaderId = bob,
                members = listOf(PartyMember(bob, 0L), PartyMember(leader, 1L)),
                formedAtTick = 0L,
            )
        )
        val invite = fx.seedInvite(leader, alice)

        val rejection = assertIs<WorldRejection.PartyInviteVoid>(
            reducePartyRespond(
                core, SocialCommand.PartyRespond(alice, invite.inviteId.value, true),
                balance, fx.partyStore, fx.invites, 5L,
            ).leftOrNull()
        )
        assertEquals(WorldRejection.PartyInviteVoid.PartyInviteVoidReason.INVITER_NOT_LEADER, rejection.reason)
    }

    @Test
    fun `accept when inviter's party is at cap voids the invite`() {
        val fx = freshFixture()
        val core = coreWith(mapOf(leader to nodeId, alice to nodeId))
        // 6-person party already at cap.
        val members = (1..5).map { PartyMember(AgentId(UUID.randomUUID()), it.toLong()) }
        fx.partyStore.create(
            Party(
                partyId = PartyId(UUID.randomUUID()),
                leaderId = leader,
                members = listOf(PartyMember(leader, 0L)) + members,
                formedAtTick = 0L,
            )
        )
        val invite = fx.seedInvite(leader, alice)

        val rejection = assertIs<WorldRejection.PartyInviteVoid>(
            reducePartyRespond(
                core, SocialCommand.PartyRespond(alice, invite.inviteId.value, true),
                balance, fx.partyStore, fx.invites, 5L,
            ).leftOrNull()
        )
        assertEquals(WorldRejection.PartyInviteVoid.PartyInviteVoidReason.PARTY_FULL, rejection.reason)
    }

    // ─────────────────────── leave_party ───────────────────────

    @Test
    fun `leave_party by leader of 3-party transfers leadership to earliest non-leader`() {
        val fx = freshFixture()
        val core = coreWith(mapOf(leader to nodeId, alice to nodeId, bob to nodeId))
        val partyId = PartyId(UUID.randomUUID())
        fx.partyStore.create(
            Party(
                partyId = partyId,
                leaderId = leader,
                members = listOf(
                    PartyMember(leader, joinedAtTick = 0L),
                    PartyMember(alice, joinedAtTick = 5L),
                    PartyMember(bob, joinedAtTick = 10L),
                ),
                formedAtTick = 0L,
            )
        )

        val out = reduceLeaveParty(
            core, SocialCommand.LeaveParty(leader),
            fx.partyStore, fx.invites, tick = 50L,
        ).getOrNull()!!

        val left = assertIs<SocialEvent.PartyLeft>(out.events[0])
        assertEquals(SocialEvent.PartyLeft.Reason.LEFT, left.reason)
        assertEquals(setOf(leader, alice, bob), left.listeners)
        assertEquals(alice, left.leader)

        val transfer = assertIs<SocialEvent.PartyLeadershipTransferred>(out.events[1])
        assertEquals(leader, transfer.previousLeader)
        assertEquals(alice, transfer.newLeader)
        assertEquals(setOf(alice, bob), transfer.listeners)

        assertEquals(alice, fx.partyStore.find(partyId)!!.leaderId)
        assertNull(fx.partyStore.findByAgent(leader))
    }

    @Test
    fun `leave_party by leader of 2-party auto-dissolves and emits PartyDissolved`() {
        val fx = freshFixture()
        val core = coreWith(mapOf(leader to nodeId, alice to nodeId))
        val partyId = PartyId(UUID.randomUUID())
        fx.partyStore.create(
            Party(
                partyId = partyId,
                leaderId = leader,
                members = listOf(PartyMember(leader, 0L), PartyMember(alice, 5L)),
                formedAtTick = 0L,
            )
        )

        val out = reduceLeaveParty(
            core, SocialCommand.LeaveParty(leader),
            fx.partyStore, fx.invites, tick = 50L,
        ).getOrNull()!!

        assertIs<SocialEvent.PartyLeft>(out.events[0])
        val dissolved = assertIs<SocialEvent.PartyDissolved>(out.events.last())
        assertEquals(setOf(leader, alice), dissolved.listeners)
        assertNull(fx.partyStore.find(partyId))
        assertNull(fx.partyStore.findByAgent(leader))
        assertNull(fx.partyStore.findByAgent(alice))
    }

    @Test
    fun `leave_party by leader sweeps pending invites and emits PartyInviteCancelled`() {
        val fx = freshFixture()
        val core = coreWith(mapOf(leader to nodeId, alice to nodeId, bob to nodeId))
        fx.partyStore.create(
            Party(
                partyId = PartyId(UUID.randomUUID()),
                leaderId = leader,
                members = listOf(PartyMember(leader, 0L), PartyMember(alice, 5L)),
                formedAtTick = 0L,
            )
        )
        val pending = fx.seedInvite(leader, bob)

        val out = reduceLeaveParty(
            core, SocialCommand.LeaveParty(leader),
            fx.partyStore, fx.invites, tick = 50L,
        ).getOrNull()!!

        val cancellations = out.events.filterIsInstance<SocialEvent.PartyInviteCancelled>()
        assertEquals(1, cancellations.size)
        val cancelled = cancellations.single()
        assertEquals(pending.inviteId, cancelled.inviteId)
        assertEquals(setOf(bob, leader), cancelled.listeners)
        assertNull(fx.invites.find(pending.inviteId))
    }

    @Test
    fun `leave_party by non-leader does not transfer leadership`() {
        val fx = freshFixture()
        val core = coreWith(mapOf(leader to nodeId, alice to nodeId, bob to nodeId))
        val partyId = PartyId(UUID.randomUUID())
        fx.partyStore.create(
            Party(
                partyId = partyId,
                leaderId = leader,
                members = listOf(
                    PartyMember(leader, 0L),
                    PartyMember(alice, 5L),
                    PartyMember(bob, 10L),
                ),
                formedAtTick = 0L,
            )
        )

        val out = reduceLeaveParty(
            core, SocialCommand.LeaveParty(alice),
            fx.partyStore, fx.invites, 50L,
        ).getOrNull()!!

        val left = assertIs<SocialEvent.PartyLeft>(out.events.single())
        assertEquals(leader, left.leader, "non-leader leaving keeps the leader")
        assertEquals(2, left.members.size)
    }

    @Test
    fun `leave_party rejects when agent is not in any party`() {
        val fx = freshFixture()
        val core = coreWith(mapOf(alice to nodeId))

        val rejection = reduceLeaveParty(
            core, SocialCommand.LeaveParty(alice),
            fx.partyStore, fx.invites, 1L,
        ).leftOrNull()

        assertEquals(WorldRejection.NotInAnyParty(alice), rejection)
    }

    // ─────────────────────── kick_member ───────────────────────

    @Test
    fun `kick by leader removes the target and emits PartyLeft with KICKED reason`() {
        val fx = freshFixture()
        val core = coreWith(mapOf(leader to nodeId, alice to nodeId, bob to nodeId))
        fx.partyStore.create(
            Party(
                partyId = PartyId(UUID.randomUUID()),
                leaderId = leader,
                members = listOf(PartyMember(leader, 0L), PartyMember(alice, 5L), PartyMember(bob, 10L)),
                formedAtTick = 0L,
            )
        )

        val out = reduceKickPartyMember(
            core, SocialCommand.KickPartyMember(leader, alice),
            fx.partyStore, fx.invites, 50L,
        ).getOrNull()!!

        val left = assertIs<SocialEvent.PartyLeft>(out.events.single())
        assertEquals(SocialEvent.PartyLeft.Reason.KICKED, left.reason)
        assertEquals(alice, left.leaver)
        assertEquals(setOf(leader, alice, bob), left.listeners)
        assertNull(fx.partyStore.findByAgent(alice))
    }

    @Test
    fun `kick rejects self-kick`() {
        val fx = freshFixture()
        val core = coreWith(mapOf(leader to nodeId))
        fx.partyStore.create(
            Party(
                partyId = PartyId(UUID.randomUUID()),
                leaderId = leader,
                members = listOf(PartyMember(leader, 0L), PartyMember(alice, 5L)),
                formedAtTick = 0L,
            )
        )

        val rejection = reduceKickPartyMember(
            core, SocialCommand.KickPartyMember(leader, leader),
            fx.partyStore, fx.invites, 1L,
        ).leftOrNull()

        assertEquals(WorldRejection.CannotKickSelf(leader), rejection)
    }

    @Test
    fun `kick rejects when caller is not the leader`() {
        val fx = freshFixture()
        val core = coreWith(mapOf(leader to nodeId, alice to nodeId, bob to nodeId))
        val partyId = PartyId(UUID.randomUUID())
        fx.partyStore.create(
            Party(
                partyId = partyId,
                leaderId = leader,
                members = listOf(PartyMember(leader, 0L), PartyMember(alice, 5L), PartyMember(bob, 10L)),
                formedAtTick = 0L,
            )
        )

        val rejection = assertIs<WorldRejection.NotPartyLeader>(
            reduceKickPartyMember(
                core, SocialCommand.KickPartyMember(alice, bob),
                fx.partyStore, fx.invites, 1L,
            ).leftOrNull()
        )
        assertEquals(partyId.value, rejection.partyId)
    }

    @Test
    fun `kick rejects when target is not a member`() {
        val fx = freshFixture()
        val core = coreWith(mapOf(leader to nodeId, alice to nodeId, carol to nodeId))
        val partyId = PartyId(UUID.randomUUID())
        fx.partyStore.create(
            Party(
                partyId = partyId,
                leaderId = leader,
                members = listOf(PartyMember(leader, 0L), PartyMember(alice, 5L)),
                formedAtTick = 0L,
            )
        )

        val rejection = assertIs<WorldRejection.KickTargetNotPartyMember>(
            reduceKickPartyMember(
                core, SocialCommand.KickPartyMember(leader, carol),
                fx.partyStore, fx.invites, 1L,
            ).leftOrNull()
        )
        assertEquals(partyId.value, rejection.partyId)
    }

    @Test
    fun `kick that drops party to 1 member auto-dissolves`() {
        val fx = freshFixture()
        val core = coreWith(mapOf(leader to nodeId, alice to nodeId))
        val partyId = PartyId(UUID.randomUUID())
        fx.partyStore.create(
            Party(
                partyId = partyId,
                leaderId = leader,
                members = listOf(PartyMember(leader, 0L), PartyMember(alice, 5L)),
                formedAtTick = 0L,
            )
        )

        val out = reduceKickPartyMember(
            core, SocialCommand.KickPartyMember(leader, alice),
            fx.partyStore, fx.invites, 50L,
        ).getOrNull()!!

        assertIs<SocialEvent.PartyLeft>(out.events[0])
        assertIs<SocialEvent.PartyDissolved>(out.events.last())
        assertNull(fx.partyStore.find(partyId))
        assertNull(fx.partyStore.findByAgent(leader))
    }

    private inner class Fixture(
        val partyStore: InMemoryPartyStore,
        val invites: InMemoryPartyInviteStore,
        val visibleNodes: VisibleNodes,
        val agents: AgentRegistry,
    ) {
        fun seedInvite(inviter: AgentId, invitee: AgentId): PartyInvite {
            val invite = PartyInvite(
                inviteId = PartyInviteId(UUID.randomUUID()),
                inviterId = inviter,
                inviteeId = invitee,
                sentAtTick = 0L,
                expiresAtTick = balance.partyInviteTtlSeconds(),
            )
            invites.create(invite, ttlSeconds = balance.partyInviteTtlSeconds())
            return invite
        }
    }

    private class TestBalance : BalanceLookup {
        override fun moveStaminaCost(biome: Biome, climate: Climate, terrain: Terrain): Int = 1
        override fun staminaRegenPerTick(climate: Climate): Int = 1
        override fun resourceSpawnsFor(terrain: Terrain) = emptyList<dev.gvart.genesara.world.ResourceSpawnRule>()
        override fun harvestStaminaCost(item: dev.gvart.genesara.world.ItemId): Int = 1
        override fun harvestYield(item: dev.gvart.genesara.world.ItemId): Int = 1
        override fun gaugeDrainPerTick(gauge: dev.gvart.genesara.world.Gauge): Int = 1
        override fun gaugeLowThreshold(gauge: dev.gvart.genesara.world.Gauge): Int = 0
        override fun starvationDamagePerTick(): Int = 1
        override fun isWaterSource(terrain: Terrain): Boolean = false
        override fun drinkStaminaCost(): Int = 1
        override fun drinkThirstRefill(): Int = 1
        override fun sleepRegenPerOfflineTick(): Int = 1
        override fun isTraversable(terrain: Terrain): Boolean = true
    }

    private class StubVisibleNodes(private val visible: Set<NodeId>) : VisibleNodes {
        override fun visibleNodesFor(
            agent: Agent,
            currentNode: NodeId,
            activeBuildingsAtCurrentNode: List<Building>,
        ): Set<NodeId> = visible + currentNode
    }

    private class StubAgents(known: Set<AgentId>) : AgentRegistry {
        private val rows = known.associateWith { id ->
            Agent(id = id, owner = PlayerId(UUID.randomUUID()), name = "test-${id.id}")
        }

        override fun find(id: AgentId): Agent? = rows[id]
        override fun listForOwner(owner: PlayerId): List<Agent> = emptyList()
    }

    private object NoBuildings : BuildingsLookup {
        override fun byId(id: UUID): Building? = null
        override fun byNode(node: NodeId): List<Building> = emptyList()
        override fun byNodes(nodes: Set<NodeId>): Map<NodeId, List<Building>> = emptyMap()
        override fun activeStationsAt(node: NodeId, hint: BuildingCategoryHint): List<Building> = emptyList()
    }
}

// ─────────────────────── in-memory store fakes ───────────────────────

private class InMemoryPartyStore : PartyStore {
    private val byId = mutableMapOf<PartyId, Party>()
    private val byAgent = mutableMapOf<AgentId, PartyId>()

    override fun create(party: Party) {
        byId[party.partyId] = party
        for (member in party.members) byAgent[member.agentId] = party.partyId
    }

    override fun find(partyId: PartyId): Party? = byId[partyId]

    override fun findByAgent(agentId: AgentId): Party? = byAgent[agentId]?.let { byId[it] }

    override fun addMember(partyId: PartyId, member: PartyMember): Party? {
        val existing = byId[partyId] ?: return null
        val next = existing.copy(members = existing.members + member)
        byId[partyId] = next
        byAgent[member.agentId] = partyId
        return next
    }

    override fun removeMember(partyId: PartyId, agentId: AgentId): Party? {
        val existing = byId[partyId] ?: return null
        if (existing.members.none { it.agentId == agentId }) return null
        val next = existing.copy(members = existing.members.filter { it.agentId != agentId })
        byId[partyId] = next
        byAgent.remove(agentId)
        return next
    }

    override fun replaceLeader(partyId: PartyId, newLeader: AgentId): Party? {
        val existing = byId[partyId] ?: return null
        val next = existing.copy(leaderId = newLeader)
        byId[partyId] = next
        return next
    }

    override fun delete(partyId: PartyId) {
        val existing = byId.remove(partyId) ?: return
        for (member in existing.members) byAgent.remove(member.agentId)
    }
}

private class InMemoryPartyInviteStore : PartyInviteStore {
    private val byId = mutableMapOf<PartyInviteId, PartyInvite>()

    override fun create(invite: PartyInvite, ttlSeconds: Long) {
        byId[invite.inviteId] = invite
    }

    override fun find(inviteId: PartyInviteId): PartyInvite? = byId[inviteId]

    override fun findByInvitee(inviteeId: AgentId): List<PartyInvite> =
        byId.values.filter { it.inviteeId == inviteeId }

    override fun findByInviter(inviterId: AgentId): List<PartyInvite> =
        byId.values.filter { it.inviterId == inviterId }

    override fun delete(inviteId: PartyInviteId) {
        byId.remove(inviteId)
    }

    override fun deleteAllByInviter(inviterId: AgentId): List<PartyInvite> {
        val toRemove = byId.values.filter { it.inviterId == inviterId }
        toRemove.forEach { byId.remove(it.inviteId) }
        return toRemove
    }
}
