package dev.gvart.genesara.api.internal.rest

import dev.gvart.genesara.account.PlayerId
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.player.Agent
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
import java.time.Duration
import kotlin.test.assertEquals

class PublicStatsControllerTest {

    @Test
    fun `stats returns current tick, fixed interval, online count, and total count`() {
        val controller = PublicStatsController(
            tick = StubTickClock(currentTick = 42L),
            world = StubWorldCounting(activeAgents = 7L),
            agents = StubRegistryCounting(totalAgents = 25L),
            tickInterval = Duration.ofSeconds(1),
        )

        val response = controller.stats()

        assertEquals(42L, response.tick)
        assertEquals(1000L, response.tickIntervalMs)
        assertEquals(7L, response.onlineAgents)
        assertEquals(25L, response.totalAgents)
    }

    @Test
    fun `stats returns zero counts when the world is empty`() {
        val controller = PublicStatsController(
            tick = StubTickClock(currentTick = 0L),
            world = StubWorldCounting(activeAgents = 0L),
            agents = StubRegistryCounting(totalAgents = 0L),
            tickInterval = Duration.ofMillis(500),
        )

        val response = controller.stats()

        assertEquals(0L, response.tick)
        assertEquals(500L, response.tickIntervalMs)
        assertEquals(0L, response.onlineAgents)
        assertEquals(0L, response.totalAgents)
    }

    private class StubTickClock(private val currentTick: Long) : TickClock {
        override fun currentTick(): Long = currentTick
    }

    private class StubWorldCounting(private val activeAgents: Long) : WorldQueryGateway {
        override fun activeAgentCount(): Long = activeAgents
        override fun locationOf(agent: AgentId): NodeId? = null
        override fun activePositionOf(agent: AgentId): NodeId? = null
        override fun bodyOf(agent: AgentId): BodyView? = null
        override fun node(id: NodeId): Node? = null
        override fun region(id: RegionId): Region? = null
        override fun nodesWithin(origin: NodeId, radius: Int): Set<NodeId> = emptySet()
        override fun randomSpawnableNode(): NodeId? = null
        override fun starterNodeFor(race: RaceId): NodeId? = null
        override fun inventoryOf(agent: AgentId): InventoryView = InventoryView(emptyList())
        override fun resourcesAt(nodeId: NodeId, tick: Long): NodeResources = NodeResources.EMPTY
        override fun groundItemsAt(nodeId: NodeId): List<dev.gvart.genesara.world.GroundItemView> = emptyList()
        override fun currentTickFor(agent: AgentId): Long = 0L
        override fun activeAgentsAtNodes(nodeIds: Set<NodeId>): Map<NodeId, List<AgentId>> = emptyMap()
    }

    private class StubRegistryCounting(private val totalAgents: Long) : AgentRegistry {
        override fun find(id: AgentId): Agent? = null
        override fun listForOwner(owner: PlayerId): List<Agent> = emptyList()
        override fun totalCount(): Long = totalAgents
    }
}
