package dev.gvart.genesara.api.internal.rest

import dev.gvart.genesara.account.Player
import dev.gvart.genesara.account.PlayerId
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityTracker
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
import dev.gvart.genesara.world.WorldAgentPurger
import dev.gvart.genesara.world.WorldQueryGateway
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentManagementControllerTest {

    private val player = Player(
        id = PlayerId(UUID.randomUUID()),
        username = "alice",
        apiToken = "plr_token",
    )
    private val activeAgent = Agent(id = AgentId(UUID.randomUUID()), owner = player.id, name = "Spawned", level = 3)
    private val idleAgent = Agent(id = AgentId(UUID.randomUUID()), owner = player.id, name = "Despawned", level = 1)

    private val body = BodyView(
        hp = 50, maxHp = 100,
        stamina = 50, maxStamina = 100,
        mana = 0, maxMana = 0,
        hunger = 100, maxHunger = 100,
        thirst = 100, maxThirst = 100,
        sleep = 100, maxSleep = 100,
    )

    @Test
    fun `list returns one summary per owned agent with live body for spawned and last-location for despawned`() {
        val registry = StubRegistry(player.id, listOf(activeAgent, idleAgent))
        val world = StubWorld(
            bodies = mapOf(activeAgent.id to body),
            locations = mapOf(activeAgent.id to NodeId(11L), idleAgent.id to NodeId(7L)),
            activePositions = mapOf(activeAgent.id to NodeId(11L)),
        )
        val activity = StubActivity(mapOf(activeAgent.id to Instant.parse("2026-05-02T12:00:00Z")))
        val controller = AgentManagementController(registry, world, activity, NoopPurger)

        val out = controller.list(player)

        assertEquals(2, out.size)
        val active = assertNotNull(out.firstOrNull { it.agentId == activeAgent.id.id })
        val idle = assertNotNull(out.firstOrNull { it.agentId == idleAgent.id.id })
        assertNotNull(active.gauges)
        assertEquals(11L, active.locationNodeId)
        assertTrue(active.spawned)
        assertNotNull(active.lastActiveAt)

        assertNull(idle.gauges)
        assertEquals(7L, idle.locationNodeId)
        assertFalse(idle.spawned)
        assertNull(idle.lastActiveAt)
    }

    @Test
    fun `list returns empty when the player owns no agents and skips the activity batch call`() {
        val registry = StubRegistry(player.id, emptyList())
        val activity = SpyActivity()
        val controller = AgentManagementController(registry, StubWorld(), activity, NoopPurger)

        val out = controller.list(player)

        assertTrue(out.isEmpty())
        assertEquals(0, activity.batchCalls)
    }

    @Test
    fun `delete purges world rows, forgets activity, deletes the agent row`() {
        val registry = TrackingRegistry(player.id, mutableMapOf(activeAgent.id to activeAgent))
        val purger = RecordingPurger()
        val activity = TrackingActivity()
        val controller = AgentManagementController(registry, StubWorld(), activity, purger)

        val response = controller.delete(player, activeAgent.id.id)

        assertEquals(HttpStatus.NO_CONTENT, response.statusCode)
        assertEquals(listOf(activeAgent.id), purger.purged)
        assertEquals(listOf(activeAgent.id), activity.forgotten)
        assertEquals(listOf(activeAgent.id), registry.deleted)
    }

    @Test
    fun `delete returns 404 when the agent does not exist`() {
        val registry = TrackingRegistry(player.id, mutableMapOf())
        val controller = AgentManagementController(registry, StubWorld(), TrackingActivity(), RecordingPurger())

        val ex = assertThrows<ResponseStatusException> { controller.delete(player, UUID.randomUUID()) }

        assertEquals(HttpStatus.NOT_FOUND, ex.statusCode)
    }

    @Test
    fun `delete returns 404 when the agent belongs to another player so existence is not leaked`() {
        val foreign = Agent(id = AgentId(UUID.randomUUID()), owner = PlayerId(UUID.randomUUID()), name = "Theirs")
        val registry = TrackingRegistry(player.id, mutableMapOf(foreign.id to foreign))
        val purger = RecordingPurger()
        val controller = AgentManagementController(registry, StubWorld(), TrackingActivity(), purger)

        val ex = assertThrows<ResponseStatusException> { controller.delete(player, foreign.id.id) }

        assertEquals(HttpStatus.NOT_FOUND, ex.statusCode)
        assertTrue(purger.purged.isEmpty())
    }

    private class StubRegistry(
        private val owner: PlayerId,
        private val agents: List<Agent>,
    ) : AgentRegistry {
        override fun find(id: AgentId): Agent? = agents.firstOrNull { it.id == id }
        override fun listForOwner(o: PlayerId): List<Agent> = if (o == owner) agents else emptyList()
    }

    private class TrackingRegistry(
        private val owner: PlayerId,
        private val rows: MutableMap<AgentId, Agent>,
    ) : AgentRegistry {
        val deleted = mutableListOf<AgentId>()
        override fun find(id: AgentId): Agent? = rows[id]
        override fun listForOwner(o: PlayerId): List<Agent> = rows.values.filter { it.owner == o }
        override fun delete(agentId: AgentId): Boolean {
            deleted += agentId
            return rows.remove(agentId) != null
        }
    }

    private class StubWorld(
        private val bodies: Map<AgentId, BodyView> = emptyMap(),
        private val locations: Map<AgentId, NodeId> = emptyMap(),
        private val activePositions: Map<AgentId, NodeId> = emptyMap(),
    ) : WorldQueryGateway {
        override fun locationOf(agent: AgentId): NodeId? = locations[agent]
        override fun activePositionOf(agent: AgentId): NodeId? = activePositions[agent]
        override fun bodyOf(agent: AgentId): BodyView? = bodies[agent]
        override fun node(id: NodeId): Node? = null
        override fun region(id: RegionId): Region? = null
        override fun nodesWithin(origin: NodeId, radius: Int): Set<NodeId> = emptySet()
        override fun randomSpawnableNode(): NodeId? = null
        override fun starterNodeFor(race: RaceId): NodeId? = null
        override fun inventoryOf(agent: AgentId): InventoryView = InventoryView(emptyList())
        override fun resourcesAt(nodeId: NodeId, tick: Long): NodeResources = NodeResources.EMPTY
    }

    private class StubActivity(private val rows: Map<AgentId, Instant>) : AgentActivityTracker {
        override fun touch(agent: AgentId) {}
        override fun staleAgents(olderThan: Instant): List<AgentId> = emptyList()
        override fun lastActiveAt(agent: AgentId): Instant? = rows[agent]
        override fun lastActiveBatch(ids: Collection<AgentId>): Map<AgentId, Instant> =
            rows.filterKeys { it in ids }
        override fun forget(agent: AgentId) {}
    }

    private class SpyActivity : AgentActivityTracker {
        var batchCalls = 0
        override fun touch(agent: AgentId) {}
        override fun staleAgents(olderThan: Instant): List<AgentId> = emptyList()
        override fun lastActiveAt(agent: AgentId): Instant? = null
        override fun lastActiveBatch(ids: Collection<AgentId>): Map<AgentId, Instant> {
            batchCalls++
            return emptyMap()
        }
        override fun forget(agent: AgentId) {}
    }

    private class TrackingActivity : AgentActivityTracker {
        val forgotten = mutableListOf<AgentId>()
        override fun touch(agent: AgentId) {}
        override fun staleAgents(olderThan: Instant): List<AgentId> = emptyList()
        override fun lastActiveAt(agent: AgentId): Instant? = null
        override fun lastActiveBatch(ids: Collection<AgentId>): Map<AgentId, Instant> = emptyMap()
        override fun forget(agent: AgentId) {
            forgotten += agent
        }
    }

    private object NoopPurger : WorldAgentPurger {
        override fun purge(agent: AgentId) {}
    }

    private class RecordingPurger : WorldAgentPurger {
        val purged = mutableListOf<AgentId>()
        override fun purge(agent: AgentId) {
            purged += agent
        }
    }
}
