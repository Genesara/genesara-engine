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
import dev.gvart.genesara.world.PartyId
import dev.gvart.genesara.world.Region
import dev.gvart.genesara.world.RegionId
import dev.gvart.genesara.world.Terrain
import dev.gvart.genesara.world.Vec3
import dev.gvart.genesara.world.VisibleNodes
import dev.gvart.genesara.world.WorldId
import dev.gvart.genesara.world.commands.SocialCommand
import dev.gvart.genesara.world.events.SocialEvent
import dev.gvart.genesara.world.internal.balance.BalanceLookup
import dev.gvart.genesara.world.internal.worldstate.slices.CoreSlice
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end invite → accept → leave → re-form flow against real Redis stores.
 * Mirrors the trade-flow integration test pattern: pure reducer composition
 * over the real backing stores so the Redis serialization invariants are
 * exercised.
 */
@Testcontainers
class PartyFlowIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val redis: GenericContainer<*> =
            GenericContainer(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379)
    }

    private lateinit var connectionFactory: LettuceConnectionFactory
    private lateinit var template: StringRedisTemplate
    private lateinit var partyStore: RedisPartyStore
    private lateinit var inviteStore: RedisPartyInviteStore

    private val leader = AgentId(UUID.randomUUID())
    private val alice = AgentId(UUID.randomUUID())
    private val bob = AgentId(UUID.randomUUID())
    private val nodeId = NodeId(1L)
    private val regionId = RegionId(1L)
    private val balance = TestBalance()
    private val agents = StubAgents(setOf(leader, alice, bob))
    private val vision = AlwaysVisible
    private val buildings = NoBuildings

    @BeforeEach
    fun setUp() {
        connectionFactory = LettuceConnectionFactory(redis.host, redis.firstMappedPort).apply {
            afterPropertiesSet()
        }
        template = StringRedisTemplate(connectionFactory)
        template.connectionFactory!!.connection.serverCommands().flushDb()
        partyStore = RedisPartyStore(template)
        inviteStore = RedisPartyInviteStore(template)
    }

    @AfterEach
    fun tearDown() {
        connectionFactory.destroy()
    }

    @Test
    fun `invite then accept materializes party in Redis with both members`() {
        val core = coreWith(mapOf(leader to nodeId, alice to nodeId))
        val invite = reducePartyInvite(
            core, SocialCommand.PartyInvite(leader, listOf(alice)),
            balance, partyStore, inviteStore, vision, agents, buildings,
            tickIntervalSeconds = 5L, tick = 10L,
        ).getOrNull()!!.events.filterIsInstance<SocialEvent.PartyInviteReceived>().single()

        val accept = reducePartyRespond(
            core, SocialCommand.PartyRespond(alice, invite.inviteId.value, accept = true),
            balance, partyStore, inviteStore, tick = 20L,
        ).getOrNull()!!

        val joined = assertIs<SocialEvent.PartyJoined>(accept.events.single())
        val party = partyStore.findByAgent(leader)!!
        assertEquals(party.partyId, joined.partyId)
        assertEquals(setOf(leader, alice), party.memberIds())
        assertEquals(leader, party.leaderId)
        assertNull(inviteStore.find(invite.inviteId), "invite consumed on accept")
    }

    @Test
    fun `expired invite resolves as PartyInviteNotFound on accept`() {
        val core = coreWith(mapOf(leader to nodeId, alice to nodeId))
        // Stand up the invite with a 1-second TTL by temporarily lowering the balance value.
        val shortTtl = TestBalanceShortInviteTtl(1L)
        val invite = reducePartyInvite(
            core, SocialCommand.PartyInvite(leader, listOf(alice)),
            shortTtl, partyStore, inviteStore, vision, agents, buildings,
            tickIntervalSeconds = 5L, tick = 10L,
        ).getOrNull()!!.events.filterIsInstance<SocialEvent.PartyInviteReceived>().single()

        Thread.sleep(1_500)

        val rejection = reducePartyRespond(
            core, SocialCommand.PartyRespond(alice, invite.inviteId.value, accept = true),
            balance, partyStore, inviteStore, tick = 20L,
        ).leftOrNull()

        assertIs<dev.gvart.genesara.world.WorldRejection.PartyInviteNotFound>(rejection)
        assertNull(partyStore.findByAgent(alice))
    }

    @Test
    fun `leader leaves 3-party then can re-form as solo and invite again`() {
        val core = coreWith(mapOf(leader to nodeId, alice to nodeId, bob to nodeId))
        // Build party leader+alice+bob through two accepts.
        val invite1 = reducePartyInvite(
            core, SocialCommand.PartyInvite(leader, listOf(alice)),
            balance, partyStore, inviteStore, vision, agents, buildings, 5L, 10L,
        ).getOrNull()!!.events.filterIsInstance<SocialEvent.PartyInviteReceived>().single()
        reducePartyRespond(
            core, SocialCommand.PartyRespond(alice, invite1.inviteId.value, true),
            balance, partyStore, inviteStore, 20L,
        )
        val invite2 = reducePartyInvite(
            core, SocialCommand.PartyInvite(leader, listOf(bob)),
            balance, partyStore, inviteStore, vision, agents, buildings, 5L, 30L,
        ).getOrNull()!!.events.filterIsInstance<SocialEvent.PartyInviteReceived>().single()
        reducePartyRespond(
            core, SocialCommand.PartyRespond(bob, invite2.inviteId.value, true),
            balance, partyStore, inviteStore, 40L,
        )

        val partyBefore = partyStore.findByAgent(leader)!!
        assertEquals(3, partyBefore.size)

        // Leader leaves → leadership transfers to alice (joined first).
        val leave = reduceLeaveParty(
            core, SocialCommand.LeaveParty(leader),
            partyStore, inviteStore, tick = 50L,
        ).getOrNull()!!
        assertTrue(leave.events.any { it is SocialEvent.PartyLeadershipTransferred })
        assertEquals(alice, partyStore.findByAgent(alice)!!.leaderId)
        assertNull(partyStore.findByAgent(leader))

        // Leader, now solo, sends a fresh invite to bob. But bob is still in the original party.
        val rejection = reducePartyInvite(
            core, SocialCommand.PartyInvite(leader, listOf(bob)),
            balance, partyStore, inviteStore, vision, agents, buildings, 5L, 60L,
        ).leftOrNull()
        assertIs<dev.gvart.genesara.world.WorldRejection.InviteeAlreadyInParty>(rejection)
    }

    @Test
    fun `leader explicit leave sweeps pending invites and emits cancellation events`() {
        val core = coreWith(mapOf(leader to nodeId, alice to nodeId, bob to nodeId))
        // Leader + alice in party, leader has pending invite to bob.
        val a1 = reducePartyInvite(
            core, SocialCommand.PartyInvite(leader, listOf(alice)),
            balance, partyStore, inviteStore, vision, agents, buildings, 5L, 10L,
        ).getOrNull()!!.events.filterIsInstance<SocialEvent.PartyInviteReceived>().single()
        reducePartyRespond(
            core, SocialCommand.PartyRespond(alice, a1.inviteId.value, true),
            balance, partyStore, inviteStore, 20L,
        )
        val b1 = reducePartyInvite(
            core, SocialCommand.PartyInvite(leader, listOf(bob)),
            balance, partyStore, inviteStore, vision, agents, buildings, 5L, 30L,
        ).getOrNull()!!.events.filterIsInstance<SocialEvent.PartyInviteReceived>().single()

        // Leader leaves party of 2 — auto-dissolve; sweep invites.
        val leave = reduceLeaveParty(
            core, SocialCommand.LeaveParty(leader),
            partyStore, inviteStore, tick = 50L,
        ).getOrNull()!!

        val cancelled = leave.events.filterIsInstance<SocialEvent.PartyInviteCancelled>().single()
        assertEquals(b1.inviteId, cancelled.inviteId)
        assertNull(inviteStore.find(b1.inviteId))
        assertNull(partyStore.findByAgent(leader))
        assertNull(partyStore.findByAgent(alice))
    }

    @Test
    fun `kick by leader on party of 3 leaves a 2-party with the leader intact`() {
        val core = coreWith(mapOf(leader to nodeId, alice to nodeId, bob to nodeId))
        val a1 = reducePartyInvite(
            core, SocialCommand.PartyInvite(leader, listOf(alice)),
            balance, partyStore, inviteStore, vision, agents, buildings, 5L, 10L,
        ).getOrNull()!!.events.filterIsInstance<SocialEvent.PartyInviteReceived>().single()
        reducePartyRespond(core, SocialCommand.PartyRespond(alice, a1.inviteId.value, true), balance, partyStore, inviteStore, 20L)
        val b1 = reducePartyInvite(
            core, SocialCommand.PartyInvite(leader, listOf(bob)),
            balance, partyStore, inviteStore, vision, agents, buildings, 5L, 30L,
        ).getOrNull()!!.events.filterIsInstance<SocialEvent.PartyInviteReceived>().single()
        reducePartyRespond(core, SocialCommand.PartyRespond(bob, b1.inviteId.value, true), balance, partyStore, inviteStore, 40L)
        val partyId = partyStore.findByAgent(leader)!!.partyId

        val kick = reduceKickPartyMember(
            core, SocialCommand.KickPartyMember(leader, bob),
            partyStore, inviteStore, 50L,
        ).getOrNull()!!

        val left = assertIs<SocialEvent.PartyLeft>(kick.events.single())
        assertEquals(SocialEvent.PartyLeft.Reason.KICKED, left.reason)
        assertEquals(bob, left.leaver)
        val after = partyStore.find(partyId)!!
        assertEquals(2, after.size)
        assertEquals(leader, after.leaderId)
        assertNull(partyStore.findByAgent(bob))
    }

    private fun coreWith(positions: Map<AgentId, NodeId>): CoreSlice = CoreSlice(
        regions = mapOf(regionId to Region(
            id = regionId, worldId = WorldId(1L), sphereIndex = 0,
            biome = Biome.PLAINS, climate = Climate.OCEANIC,
            centroid = Vec3(0.0, 0.0, 1.0), faceVertices = emptyList(), neighbors = emptySet(),
        )),
        nodes = mapOf(
            nodeId to Node(nodeId, regionId, q = 0, r = 0, terrain = Terrain.FOREST, adjacency = emptySet()),
        ),
        positions = positions,
    )

    private open class TestBalance : BalanceLookup {
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

    private class TestBalanceShortInviteTtl(private val ttl: Long) : TestBalance() {
        override fun partyInviteTtlSeconds(): Long = ttl
    }

    private object AlwaysVisible : VisibleNodes {
        override fun visibleNodesFor(
            agent: Agent,
            currentNode: NodeId,
            activeBuildingsAtCurrentNode: List<Building>,
        ): Set<NodeId> = setOf(currentNode, NodeId(1L), NodeId(2L), NodeId(99L))
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
