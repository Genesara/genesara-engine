package dev.gvart.genesara.api.internal.rest

import dev.gvart.genesara.account.Player
import dev.gvart.genesara.account.PlayerId
import dev.gvart.genesara.player.Agent
import dev.gvart.genesara.player.AgentAttributes
import dev.gvart.genesara.player.AgentClass
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.player.ClassOffer
import dev.gvart.genesara.player.RaceId
import dev.gvart.genesara.world.AgentSafeNodeGateway
import dev.gvart.genesara.world.BodyView
import dev.gvart.genesara.world.InventoryView
import dev.gvart.genesara.world.Node
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.NodeResources
import dev.gvart.genesara.world.Region
import dev.gvart.genesara.world.RegionId
import dev.gvart.genesara.world.WorldQueryGateway
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AgentDetailControllerTest {

    private val player = Player(id = PlayerId(UUID.randomUUID()), username = "alice", apiToken = "plr_x")
    private val agent = Agent(
        id = AgentId(UUID.randomUUID()),
        owner = player.id,
        name = "Ada",
        classId = AgentClass.SCOUT,
        race = RaceId("human_commoner"),
        level = 4,
        xpCurrent = 30,
        xpToNext = 400,
        unspentAttributePoints = 2,
        attributes = AgentAttributes.DEFAULT,
        authority = 7,
        fame = 12,
        offeredClasses = ClassOffer(AgentClass.SCOUT, AgentClass.HUNTER),
    )

    @Test
    fun `detail returns the active position when the agent is spawned`() {
        val world = StubWorld(
            bodies = mapOf(agent.id to body()),
            activePositions = mapOf(agent.id to NodeId(11L)),
            lastLocations = mapOf(agent.id to NodeId(11L)),
            tickByAgent = mapOf(agent.id to 99L),
        )
        val controller = AgentDetailController(resolver(agent), world, safeNodes(agent.id to NodeId(33L)))

        val response = controller.detail(player, agent.id.id)

        assertEquals(agent.id.id, response.agentId)
        assertEquals("Ada", response.name)
        assertEquals(AgentClass.SCOUT, response.classId)
        assertEquals(4, response.level)
        assertEquals(30, response.xp.current)
        assertEquals(400, response.xp.toNext)
        assertEquals(11L, response.location)
        assertEquals(33L, response.safeNode)
        assertEquals(99L, response.tick)
        assertEquals(7, response.authority)
        assertEquals(12, response.fame)
        assertEquals(listOf(AgentClass.SCOUT, AgentClass.HUNTER), response.pendingClassChoice)

        val gauges = assertNotNull(response.gauges)
        assertEquals(50, gauges.hp.current)
        assertEquals(100, gauges.hp.max)
    }

    @Test
    fun `detail falls back to the last-known location when the agent is despawned`() {
        val world = StubWorld(
            bodies = emptyMap(),
            activePositions = emptyMap(),
            lastLocations = mapOf(agent.id to NodeId(7L)),
        )
        val controller = AgentDetailController(resolver(agent), world, safeNodes())

        val response = controller.detail(player, agent.id.id)

        assertEquals(7L, response.location)
        assertNull(response.gauges)
        assertNull(response.safeNode)
    }

    private fun body() = BodyView(
        hp = 50, maxHp = 100,
        stamina = 60, maxStamina = 100,
        mana = 0, maxMana = 0,
        hunger = 80, maxHunger = 100,
        thirst = 70, maxThirst = 100,
        sleep = 90, maxSleep = 100,
    )

    private fun resolver(agent: Agent) = OwnedAgentResolver(SingleAgentRegistry(agent))

    private fun safeNodes(vararg entries: Pair<AgentId, NodeId>): AgentSafeNodeGateway {
        val map = entries.toMap()
        return object : AgentSafeNodeGateway {
            override fun set(agentId: AgentId, nodeId: NodeId, tick: Long) {}
            override fun find(agentId: AgentId): NodeId? = map[agentId]
            override fun clear(agentId: AgentId) {}
        }
    }

    private class SingleAgentRegistry(private val agent: Agent) : AgentRegistry {
        override fun find(id: AgentId): Agent? = if (id == agent.id) agent else null
        override fun listForOwner(owner: PlayerId): List<Agent> = emptyList()
    }

    private class StubWorld(
        private val bodies: Map<AgentId, BodyView> = emptyMap(),
        private val activePositions: Map<AgentId, NodeId> = emptyMap(),
        private val lastLocations: Map<AgentId, NodeId> = emptyMap(),
        private val tickByAgent: Map<AgentId, Long> = emptyMap(),
    ) : WorldQueryGateway {
        override fun locationOf(agent: AgentId): NodeId? = lastLocations[agent]
        override fun activePositionOf(agent: AgentId): NodeId? = activePositions[agent]
        override fun bodyOf(agent: AgentId): BodyView? = bodies[agent]
        override fun node(id: NodeId): Node? = null
        override fun region(id: RegionId): Region? = null
        override fun nodesWithin(origin: NodeId, radius: Int): Set<NodeId> = emptySet()
        override fun randomSpawnableNode(): NodeId? = null
        override fun starterNodeFor(race: RaceId): NodeId? = null
        override fun inventoryOf(agent: AgentId): InventoryView = InventoryView(emptyList())
        override fun resourcesAt(nodeId: NodeId, tick: Long): NodeResources = NodeResources.EMPTY
        override fun groundItemsAt(nodeId: NodeId): List<dev.gvart.genesara.world.GroundItemView> = emptyList()
        override fun currentTickFor(agent: AgentId): Long = tickByAgent[agent] ?: 0L
        override fun activeAgentsAtNodes(nodeIds: Set<NodeId>): Map<NodeId, List<AgentId>> = emptyMap()
    }
}
