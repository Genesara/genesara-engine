package dev.gvart.genesara.api.internal.rest

import dev.gvart.genesara.account.Player
import dev.gvart.genesara.account.PlayerId
import dev.gvart.genesara.player.Agent
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.player.RelationshipAdjustmentOutcome
import dev.gvart.genesara.player.RelationshipRow
import dev.gvart.genesara.player.RelationshipsGateway
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AgentRelationshipsControllerTest {

    private val player = Player(id = PlayerId(UUID.randomUUID()), username = "alice", apiToken = "plr_x")
    private val agent = Agent(id = AgentId(UUID.randomUUID()), owner = player.id, name = "Ada", authority = 8, fame = 15)
    private val friend = Agent(id = AgentId(UUID.randomUUID()), owner = PlayerId(UUID.randomUUID()), name = "Bob")
    private val deletedFriend = AgentId(UUID.randomUUID())

    @Test
    fun `relationships project per-pair scores with display names plus the agent's authority and fame`() {
        val gateway = StubRelationships(
            mapOf(friend.id to RelationshipRow(score = 42, lastChangedAtTick = 7)),
        )
        val registry = MultiRegistry(listOf(agent, friend))
        val controller = AgentRelationshipsController(OwnedAgentResolver(registry), gateway, registry)

        val response = controller.relationships(player, agent.id.id)

        assertEquals(8, response.authority)
        assertEquals(15, response.fame)
        val entry = response.entries.single()
        assertEquals("agent:${friend.id.id}", entry.agentId)
        assertEquals("Bob", entry.agentName)
        assertEquals(42, entry.score)
        assertEquals(7L, entry.lastChangedAtTick)
    }

    @Test
    fun `relationships return a null agentName when the other agent's row is gone`() {
        val gateway = StubRelationships(
            mapOf(deletedFriend to RelationshipRow(score = -10, lastChangedAtTick = 3)),
        )
        val registry = MultiRegistry(listOf(agent))
        val controller = AgentRelationshipsController(OwnedAgentResolver(registry), gateway, registry)

        val entry = controller.relationships(player, agent.id.id).entries.single()

        assertEquals("agent:${deletedFriend.id}", entry.agentId)
        assertNull(entry.agentName)
    }

    private class MultiRegistry(private val agents: List<Agent>) : AgentRegistry {
        override fun find(id: AgentId): Agent? = agents.firstOrNull { it.id == id }
        override fun listForOwner(owner: PlayerId): List<Agent> = agents.filter { it.owner == owner }
    }

    private class StubRelationships(private val rows: Map<AgentId, RelationshipRow>) : RelationshipsGateway {
        override fun adjust(a: AgentId, b: AgentId, delta: Int, tick: Long) =
            RelationshipAdjustmentOutcome(currentScore = 0)
        override fun adjustMany(anchor: AgentId, others: Collection<AgentId>, delta: Int, tick: Long) {}
        override fun find(a: AgentId, b: AgentId): RelationshipRow? = null
        override fun scoresFor(agentId: AgentId): Map<AgentId, RelationshipRow> = rows
    }
}
