package dev.gvart.genesara.world.body.internal.admin

import com.zaxxer.hikari.HikariDataSource
import dev.gvart.genesara.account.PlayerId
import dev.gvart.genesara.player.Agent
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentProfile
import dev.gvart.genesara.player.AgentProfileLookup
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.player.DeathPenaltyOutcome
import dev.gvart.genesara.player.RaceId
import dev.gvart.genesara.world.AgentBodyAdminResult
import dev.gvart.genesara.world.AgentSafeNodeGateway
import dev.gvart.genesara.world.Biome
import dev.gvart.genesara.world.Climate
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.Terrain
import dev.gvart.genesara.world.WorldQueryGateway
import dev.gvart.genesara.world.body.internal.death.JooqAgentSafeNodeGateway
import dev.gvart.genesara.world.events.BodyEvent
import dev.gvart.genesara.world.events.CoreEvent
import dev.gvart.genesara.world.internal.jooq.tables.references.AGENT_BODIES
import dev.gvart.genesara.world.internal.jooq.tables.references.AGENT_POSITIONS
import dev.gvart.genesara.world.internal.jooq.tables.references.AGENT_SAFE_NODES
import dev.gvart.genesara.world.internal.jooq.tables.references.NODES
import dev.gvart.genesara.world.internal.jooq.tables.references.REGIONS
import dev.gvart.genesara.world.internal.jooq.tables.references.WORLDS
import dev.gvart.genesara.world.internal.testsupport.WorldFlyway
import org.jooq.DSLContext
import org.jooq.JSON
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEvent
import org.springframework.context.ApplicationEventPublisher
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Testcontainers
class JooqAgentBodyAdminGatewayIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("body_admin_it")
            .withUsername("test")
            .withPassword("test")

        private lateinit var dataSource: HikariDataSource
        private lateinit var dsl: DSLContext

        @BeforeAll
        @JvmStatic
        fun migrateOnce() {
            dataSource = WorldFlyway.pooledDataSource(postgres)
            WorldFlyway.migrate(dataSource)
            dsl = DSL.using(dataSource, SQLDialect.POSTGRES)
        }

        @AfterAll
        @JvmStatic
        fun closePool() {
            dataSource.close()
        }
    }

    private val agent = AgentId(UUID.randomUUID())
    private val owner = PlayerId(UUID.randomUUID())
    private var worldA: Long = 0L
    private var worldB: Long = 0L
    private var nodeA1: Long = 0L
    private var nodeB1: Long = 0L
    private var nodeB2: Long = 0L

    private lateinit var publisher: RecordingPublisher
    private lateinit var safeNodes: JooqAgentSafeNodeGateway
    private lateinit var profiles: FixedProfileLookup
    private lateinit var registry: TrackingRegistry
    private lateinit var world: StubWorldQuery
    private lateinit var gateway: JooqAgentBodyAdminGateway

    @BeforeEach
    fun reset() {
        dsl.truncate(AGENT_BODIES).cascade().execute()
        dsl.truncate(AGENT_POSITIONS).cascade().execute()
        dsl.truncate(AGENT_SAFE_NODES).cascade().execute()
        dsl.truncate(NODES).cascade().execute()
        dsl.truncate(REGIONS).cascade().execute()
        dsl.truncate(WORLDS).cascade().execute()

        worldA = insertWorld("worldA")
        worldB = insertWorld("worldB")
        val regionA = insertRegion(worldA, sphereIndex = 0)
        val regionB = insertRegion(worldB, sphereIndex = 0)
        nodeA1 = insertNode(regionA, q = 0, r = 0)
        nodeB1 = insertNode(regionB, q = 0, r = 0)
        nodeB2 = insertNode(regionB, q = 1, r = 0)

        publisher = RecordingPublisher()
        safeNodes = JooqAgentSafeNodeGateway(dsl)
        profiles = FixedProfileLookup(AgentProfile(id = agent, maxHp = 80, maxStamina = 50, maxMana = 30))
        registry = TrackingRegistry(
            present = mapOf(
                agent to Agent(id = agent, owner = owner, name = "victim", race = RaceId("human_commoner")),
            ),
        )
        world = StubWorldQuery(starterByRace = emptyMap(), randomSpawnable = NodeId(nodeA1))
        gateway = JooqAgentBodyAdminGateway(dsl, publisher, safeNodes, profiles, registry, world)
    }

    @Test
    fun `setGauges clamps each pool to its max and underflow to zero`() {
        seedBody(hp = 80, stamina = 50, mana = 30, hunger = 100, thirst = 100, sleep = 100)

        val result = gateway.setGauges(
            agent,
            hp = 999,
            stamina = -10,
            mana = 0,
            hunger = 250,
            thirst = 17,
            sleep = -1,
        )

        val applied = assertIs<AgentBodyAdminResult.GaugesUpdated>(result).applied
        assertEquals(80, applied["hp"], "above-max clamped to max_hp")
        assertEquals(0, applied["stamina"], "negative clamped to 0")
        assertEquals(0, applied["mana"])
        assertEquals(100, applied["hunger"], "above max_hunger clamped to 100")
        assertEquals(17, applied["thirst"], "in-range kept verbatim")
        assertEquals(0, applied["sleep"])

        val row = readBody()
        assertEquals(80, row[AGENT_BODIES.HP])
        assertEquals(0, row[AGENT_BODIES.STAMINA])
        assertEquals(0, row[AGENT_BODIES.MANA])
        assertEquals(100, row[AGENT_BODIES.HUNGER])
        assertEquals(17, row[AGENT_BODIES.THIRST])
        assertEquals(0, row[AGENT_BODIES.SLEEP])
    }

    @Test
    fun `setGauges only touches fields that were provided`() {
        seedBody(hp = 80, stamina = 25, mana = 10, hunger = 70, thirst = 60, sleep = 50)

        gateway.setGauges(agent, hp = 50)

        val row = readBody()
        assertEquals(50, row[AGENT_BODIES.HP])
        assertEquals(25, row[AGENT_BODIES.STAMINA])
        assertEquals(10, row[AGENT_BODIES.MANA])
        assertEquals(70, row[AGENT_BODIES.HUNGER])
        assertEquals(60, row[AGENT_BODIES.THIRST])
        assertEquals(50, row[AGENT_BODIES.SLEEP])
    }

    @Test
    fun `setGauges returns AgentNotFound when no body row exists`() {
        val result = gateway.setGauges(agent, hp = 10)
        assertIs<AgentBodyAdminResult.AgentNotFound>(result)
    }

    @Test
    fun `teleport across worlds updates world_id and emits AgentMoved`() {
        seedPosition(node = nodeA1, world = worldA, active = true)

        val result = gateway.teleport(agent, NodeId(nodeB2), tick = 7L)

        val tele = assertIs<AgentBodyAdminResult.Teleported>(result)
        assertEquals(NodeId(nodeA1), tele.from)
        assertEquals(NodeId(nodeB2), tele.to)
        assertTrue(tele.crossedWorld)

        val row = readPosition()
        assertEquals(nodeB2, row[AGENT_POSITIONS.NODE_ID])
        assertEquals(worldB, row[AGENT_POSITIONS.WORLD_ID])
        assertTrue(row[AGENT_POSITIONS.ACTIVE]!!, "active flag preserved across teleport")

        val moved = assertIs<CoreEvent.AgentMoved>(publisher.events.single())
        assertEquals(agent, moved.agent)
        assertEquals(NodeId(nodeA1), moved.from)
        assertEquals(NodeId(nodeB2), moved.to)
        assertEquals(0, moved.staminaSpent)
        assertEquals(7L, moved.tick)
    }

    @Test
    fun `teleport returns NodeNotFound for an unknown destination`() {
        seedPosition(node = nodeA1, world = worldA, active = true)
        val result = gateway.teleport(agent, NodeId(9_999_999L), tick = 1L)
        assertIs<AgentBodyAdminResult.NodeNotFound>(result)
        assertTrue(publisher.events.isEmpty())
    }

    @Test
    fun `teleport creates the position row when the agent has none yet`() {
        val result = gateway.teleport(agent, NodeId(nodeA1), tick = 3L)
        val tele = assertIs<AgentBodyAdminResult.Teleported>(result)
        assertEquals(null, tele.from)
        assertFalse(tele.crossedWorld, "first-ever placement is not a world-cross")

        val row = readPosition()
        assertEquals(nodeA1, row[AGENT_POSITIONS.NODE_ID])
        assertEquals(worldA, row[AGENT_POSITIONS.WORLD_ID])
    }

    @Test
    fun `forceRespawn restores body to profile maxima and does not de-level even on empty XP bar`() {
        safeNodes.set(agent, NodeId(nodeB1), tick = 1L)
        seedBody(hp = 0, stamina = 0, mana = 0, hunger = 0, thirst = 0, sleep = 0)
        seedPosition(node = nodeA1, world = worldA, active = false)

        val result = gateway.forceRespawn(agent, tick = 9L)

        val respawn = assertIs<AgentBodyAdminResult.Respawned>(result)
        assertEquals(NodeId(nodeB1), respawn.at)
        assertTrue(respawn.fromCheckpoint)

        val body = readBody()
        assertEquals(80, body[AGENT_BODIES.HP])
        assertEquals(50, body[AGENT_BODIES.STAMINA])
        assertEquals(30, body[AGENT_BODIES.MANA])
        assertEquals(100, body[AGENT_BODIES.HUNGER])
        assertEquals(100, body[AGENT_BODIES.THIRST])
        assertEquals(100, body[AGENT_BODIES.SLEEP])

        val position = readPosition()
        assertEquals(nodeB1, position[AGENT_POSITIONS.NODE_ID])
        assertEquals(worldB, position[AGENT_POSITIONS.WORLD_ID])
        assertTrue(position[AGENT_POSITIONS.ACTIVE]!!)

        assertEquals(0, registry.deathPenaltyCallCount, "force-respawn must skip the de-level path")

        val event = assertIs<BodyEvent.AgentRespawned>(publisher.events.single())
        assertEquals(agent, event.agent)
        assertEquals(NodeId(nodeB1), event.at)
        assertTrue(event.fromCheckpoint)
        assertEquals(9L, event.tick)
    }

    @Test
    fun `forceRespawn falls back to random spawnable when no checkpoint or starter exists`() {
        seedBody(hp = 0, stamina = 0, mana = 0, hunger = 0, thirst = 0, sleep = 0)
        seedPosition(node = nodeA1, world = worldA, active = false)

        val result = gateway.forceRespawn(agent, tick = 4L)

        val respawn = assertIs<AgentBodyAdminResult.Respawned>(result)
        assertEquals(NodeId(nodeA1), respawn.at)
        assertFalse(respawn.fromCheckpoint)
    }

    @Test
    fun `forceRespawn returns AgentNotFound when no profile exists`() {
        val unknown = AgentId(UUID.randomUUID())
        val result = gateway.forceRespawn(unknown, tick = 1L)
        assertIs<AgentBodyAdminResult.AgentNotFound>(result)
        assertTrue(publisher.events.isEmpty())
    }

    private fun seedBody(hp: Int, stamina: Int, mana: Int, hunger: Int, thirst: Int, sleep: Int) {
        dsl.insertInto(AGENT_BODIES)
            .set(AGENT_BODIES.AGENT_ID, agent.id)
            .set(AGENT_BODIES.HP, hp).set(AGENT_BODIES.MAX_HP, 80)
            .set(AGENT_BODIES.STAMINA, stamina).set(AGENT_BODIES.MAX_STAMINA, 50)
            .set(AGENT_BODIES.MANA, mana).set(AGENT_BODIES.MAX_MANA, 30)
            .set(AGENT_BODIES.HUNGER, hunger).set(AGENT_BODIES.MAX_HUNGER, 100)
            .set(AGENT_BODIES.THIRST, thirst).set(AGENT_BODIES.MAX_THIRST, 100)
            .set(AGENT_BODIES.SLEEP, sleep).set(AGENT_BODIES.MAX_SLEEP, 100)
            .execute()
    }

    private fun seedPosition(node: Long, world: Long, active: Boolean) {
        dsl.insertInto(AGENT_POSITIONS)
            .set(AGENT_POSITIONS.AGENT_ID, agent.id)
            .set(AGENT_POSITIONS.NODE_ID, node)
            .set(AGENT_POSITIONS.WORLD_ID, world)
            .set(AGENT_POSITIONS.ACTIVE, active)
            .execute()
    }

    private fun readBody() = dsl.selectFrom(AGENT_BODIES).where(AGENT_BODIES.AGENT_ID.eq(agent.id)).fetchSingle()
    private fun readPosition() = dsl.selectFrom(AGENT_POSITIONS).where(AGENT_POSITIONS.AGENT_ID.eq(agent.id)).fetchSingle()

    private fun insertWorld(name: String): Long =
        dsl.insertInto(WORLDS)
            .set(WORLDS.NAME, name)
            .set(WORLDS.NODE_COUNT, 1)
            .set(WORLDS.NODE_SIZE, 1)
            .set(WORLDS.FREQUENCY, 1)
            .returningResult(WORLDS.ID)
            .fetchOne()!!.value1()!!

    private fun insertRegion(worldId: Long, sphereIndex: Int): Long =
        dsl.insertInto(REGIONS)
            .set(REGIONS.WORLD_ID, worldId)
            .set(REGIONS.SPHERE_INDEX, sphereIndex)
            .set(REGIONS.BIOME, Biome.PLAINS.name)
            .set(REGIONS.CLIMATE, Climate.CONTINENTAL.name)
            .set(REGIONS.CENTROID_X, 0.0)
            .set(REGIONS.CENTROID_Y, 0.0)
            .set(REGIONS.CENTROID_Z, 1.0)
            .set(REGIONS.FACE_VERTICES, JSON.valueOf("[]"))
            .returningResult(REGIONS.ID)
            .fetchOne()!!.value1()!!

    private fun insertNode(regionId: Long, q: Int, r: Int): Long =
        dsl.insertInto(NODES)
            .set(NODES.REGION_ID, regionId)
            .set(NODES.Q, q)
            .set(NODES.R, r)
            .set(NODES.TERRAIN, Terrain.PLAINS.name)
            .returningResult(NODES.ID)
            .fetchOne()!!.value1()!!

    private class RecordingPublisher : ApplicationEventPublisher {
        val events = mutableListOf<Any>()
        override fun publishEvent(event: ApplicationEvent) { events += event }
        override fun publishEvent(event: Any) { events += event }
    }

    private class FixedProfileLookup(private val profile: AgentProfile) : AgentProfileLookup {
        override fun find(id: AgentId): AgentProfile? = if (id == profile.id) profile else null
    }

    private class TrackingRegistry(private val present: Map<AgentId, Agent>) : AgentRegistry {
        var deathPenaltyCallCount: Int = 0
        override fun find(id: AgentId): Agent? = present[id]
        override fun listForOwner(owner: PlayerId): List<Agent> = present.values.filter { it.owner == owner }
        override fun applyDeathPenalty(agentId: AgentId, xpLossOnDeath: Int): DeathPenaltyOutcome? {
            deathPenaltyCallCount += 1
            return DeathPenaltyOutcome(xpLost = 0, deleveled = false, attributePointLost = null)
        }
    }

    private class StubWorldQuery(
        private val starterByRace: Map<RaceId, NodeId>,
        private val randomSpawnable: NodeId?,
    ) : WorldQueryGateway {
        override fun locationOf(agent: AgentId): NodeId? = null
        override fun activePositionOf(agent: AgentId): NodeId? = null
        override fun node(id: NodeId) = null
        override fun region(id: dev.gvart.genesara.world.RegionId) = null
        override fun nodesWithin(origin: NodeId, radius: Int): Set<NodeId> = emptySet()
        override fun randomSpawnableNode(): NodeId? = randomSpawnable
        override fun starterNodeFor(race: RaceId): NodeId? = starterByRace[race]
        override fun bodyOf(agent: AgentId) = null
        override fun inventoryOf(agent: AgentId) = dev.gvart.genesara.world.InventoryView(emptyList())
        override fun resourcesAt(nodeId: NodeId, tick: Long) = dev.gvart.genesara.world.NodeResources.EMPTY
        override fun groundItemsAt(nodeId: NodeId) = emptyList<dev.gvart.genesara.world.GroundItemView>()
        override fun currentTickFor(agent: AgentId): Long = 0L
        override fun activeAgentsAtNodes(nodeIds: Set<NodeId>) = emptyMap<NodeId, List<AgentId>>()
    }
}
