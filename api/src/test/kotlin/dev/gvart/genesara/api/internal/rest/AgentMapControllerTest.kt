package dev.gvart.genesara.api.internal.rest

import dev.gvart.genesara.account.Player
import dev.gvart.genesara.account.PlayerId
import dev.gvart.genesara.player.Agent
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.player.RaceId
import dev.gvart.genesara.world.AgentMapMemoryGateway
import dev.gvart.genesara.world.Biome
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.NodeMemoryUpdate
import dev.gvart.genesara.world.RecalledNode
import dev.gvart.genesara.world.RegionId
import dev.gvart.genesara.world.Terrain
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals

class AgentMapControllerTest {

    private val player = Player(id = PlayerId(UUID.randomUUID()), username = "alice", apiToken = "plr_x")
    private val agent = Agent(id = AgentId(UUID.randomUUID()), owner = player.id, name = "Ada")

    @Test
    fun `map returns one recalled-node view per stored memory entry`() {
        val recall = listOf(
            RecalledNode(NodeId(1L), RegionId(7L), q = 0, r = 0, terrain = Terrain.PLAINS, biome = Biome.PLAINS, firstSeenTick = 10, lastSeenTick = 20),
            RecalledNode(NodeId(2L), RegionId(7L), q = 1, r = 0, terrain = Terrain.HILLS, biome = null, firstSeenTick = 15, lastSeenTick = 25),
        )
        val controller = AgentMapController(OwnedAgentResolver(SingleRegistry(agent)), StubMapMemory(recall))

        val response = controller.map(player, agent.id.id)

        assertEquals(listOf(1L, 2L), response.nodes.map { it.nodeId })
        assertEquals(Terrain.PLAINS, response.nodes[0].terrain)
        assertEquals(Biome.PLAINS, response.nodes[0].biome)
        assertEquals(null, response.nodes[1].biome)
    }

    private class SingleRegistry(private val agent: Agent) : AgentRegistry {
        override fun find(id: AgentId): Agent? = if (id == agent.id) agent else null
        override fun listForOwner(owner: PlayerId): List<Agent> = emptyList()
    }

    private class StubMapMemory(private val recall: List<RecalledNode>) : AgentMapMemoryGateway {
        override fun recordVisible(agentId: AgentId, updates: Collection<NodeMemoryUpdate>, tick: Long) {}
        override fun recall(agentId: AgentId): List<RecalledNode> = recall
    }
}
