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

class AgentRelationshipsControllerTest {

    private val player = Player(id = PlayerId(UUID.randomUUID()), username = "alice", apiToken = "plr_x")
    private val agent = Agent(id = AgentId(UUID.randomUUID()), owner = player.id, name = "Ada", authority = 8, fame = 15)
    private val friend = AgentId(UUID.randomUUID())

    @Test
    fun `relationships projects per-pair scores plus the agent's own authority and fame`() {
        val gateway = StubRelationships(
            mapOf(friend to RelationshipRow(score = 42, lastChangedAtTick = 7)),
        )
        val controller = AgentRelationshipsController(OwnedAgentResolver(SingleRegistry(agent)), gateway)

        val response = controller.relationships(player, agent.id.id)

        assertEquals(8, response.authority)
        assertEquals(15, response.fame)
        assertEquals(1, response.entries.size)
        val entry = response.entries.single()
        assertEquals("agent:${friend.id}", entry.agentId)
        assertEquals(42, entry.score)
        assertEquals(7L, entry.lastChangedAtTick)
    }

    private class SingleRegistry(private val agent: Agent) : AgentRegistry {
        override fun find(id: AgentId): Agent? = if (id == agent.id) agent else null
        override fun listForOwner(owner: PlayerId): List<Agent> = emptyList()
    }

    private class StubRelationships(private val rows: Map<AgentId, RelationshipRow>) : RelationshipsGateway {
        override fun adjust(a: AgentId, b: AgentId, delta: Int, tick: Long) =
            RelationshipAdjustmentOutcome(currentScore = 0)
        override fun adjustMany(anchor: AgentId, others: Collection<AgentId>, delta: Int, tick: Long) {}
        override fun find(a: AgentId, b: AgentId): RelationshipRow? = null
        override fun scoresFor(agentId: AgentId): Map<AgentId, RelationshipRow> = rows
    }
}
