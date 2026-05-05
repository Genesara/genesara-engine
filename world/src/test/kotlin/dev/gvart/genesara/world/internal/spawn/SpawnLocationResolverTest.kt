package dev.gvart.genesara.world.internal.spawn

import dev.gvart.genesara.account.PlayerId
import dev.gvart.genesara.player.Agent
import dev.gvart.genesara.player.AgentAttributes
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.player.RaceId
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
import kotlin.test.assertNull

class SpawnLocationResolverTest {

    private val agentId = AgentId(UUID.randomUUID())
    private val race = RaceId("human_steppe")
    private val resume = NodeId(7L)
    private val starter = NodeId(11L)
    private val random = NodeId(42L)

    private val baseAgent = Agent(
        id = agentId,
        owner = PlayerId(UUID.randomUUID()),
        name = "Komar",
        race = race,
        attributes = AgentAttributes(),
    )

    @Test
    fun `prefers the agent's last-known location when one exists`() {
        val resolver = SpawnLocationResolverImpl(
            agents = StubAgents(baseAgent),
            world = StubQuery(lastLocation = resume, starterByRace = mapOf(race to starter), randomNode = random),
        )

        assertEquals(resume, resolver.resolveFor(agentId))
    }

    @Test
    fun `falls back to the race-keyed starter node when no last location is known`() {
        val resolver = SpawnLocationResolverImpl(
            agents = StubAgents(baseAgent),
            world = StubQuery(lastLocation = null, starterByRace = mapOf(race to starter), randomNode = random),
        )

        assertEquals(starter, resolver.resolveFor(agentId))
    }

    @Test
    fun `falls back to a random spawnable node when the race has no starter mapping`() {
        val resolver = SpawnLocationResolverImpl(
            agents = StubAgents(baseAgent),
            world = StubQuery(lastLocation = null, starterByRace = emptyMap(), randomNode = random),
        )

        assertEquals(random, resolver.resolveFor(agentId))
    }

    @Test
    fun `skips the starter step when the agent is unknown to the registry`() {
        val resolver = SpawnLocationResolverImpl(
            agents = StubAgents(null),
            world = StubQuery(lastLocation = null, starterByRace = mapOf(race to starter), randomNode = random),
        )

        assertEquals(random, resolver.resolveFor(agentId))
    }

    @Test
    fun `returns null when the world has no spawnable node anywhere`() {
        val resolver = SpawnLocationResolverImpl(
            agents = StubAgents(baseAgent),
            world = StubQuery(lastLocation = null, starterByRace = emptyMap(), randomNode = null),
        )

        assertNull(resolver.resolveFor(agentId))
    }

    private class StubAgents(private val agent: Agent?) : AgentRegistry {
        override fun find(id: AgentId): Agent? = agent
        override fun listForOwner(owner: PlayerId): List<Agent> = listOfNotNull(agent)
    }

    private class StubQuery(
        private val lastLocation: NodeId? = null,
        private val starterByRace: Map<RaceId, NodeId> = emptyMap(),
        private val randomNode: NodeId? = null,
    ) : WorldQueryGateway {
        override fun locationOf(agent: AgentId): NodeId? = lastLocation
        override fun activePositionOf(agent: AgentId): NodeId? = null
        override fun node(id: NodeId): Node? = null
        override fun region(id: RegionId): Region? = null
        override fun nodesWithin(origin: NodeId, radius: Int): Set<NodeId> = emptySet()
        override fun randomSpawnableNode(): NodeId? = randomNode
        override fun starterNodeFor(race: RaceId): NodeId? = starterByRace[race]
        override fun bodyOf(agent: AgentId): BodyView? = null
        override fun inventoryOf(agent: AgentId): InventoryView = InventoryView(emptyList())
        override fun resourcesAt(nodeId: NodeId, tick: Long): NodeResources = NodeResources.EMPTY
        override fun groundItemsAt(nodeId: NodeId): List<dev.gvart.genesara.world.GroundItemView> = emptyList()
    }
}
