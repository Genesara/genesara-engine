package dev.gvart.genesara.api.internal.mcp.presence

import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.Node
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.Region
import dev.gvart.genesara.world.RegionId
import dev.gvart.genesara.world.WorldCommandGateway
import dev.gvart.genesara.world.WorldQueryGateway
import dev.gvart.genesara.world.commands.WorldCommand
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PresenceReaperTest {

    private val activeAgent = AgentId(UUID.randomUUID())
    private val staleAgent = AgentId(UUID.randomUUID())
    private val ghostAgent = AgentId(UUID.randomUUID())

    private val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
    private val activity = AgentActivityRegistry(clock)
    private val gateway = RecordingGateway()
    private val query = StubQuery(active = setOf(activeAgent, staleAgent))
    private val tick = StubTickClock(currentTick = 42L)
    private val props = PresenceProperties(timeout = Duration.ofMillis(50), reaperInterval = Duration.ofMinutes(1))
    private val reaper = PresenceReaper(activity, gateway, tick, query, props, clock)

    @Test
    fun `submits UnspawnAgent for stale agents that still hold an active position`() {
        activity.touch(activeAgent)
        activity.touch(staleAgent)
        clock += props.timeout.toMillis() + 20
        activity.touch(activeAgent) // refresh activeAgent so it's NOT stale

        reaper.reap()

        assertEquals(1, gateway.submissions.size)
        val (cmd, appliesAt) = gateway.submissions.single()
        assertEquals(WorldCommand.UnspawnAgent::class, cmd::class)
        assertEquals(staleAgent, (cmd as WorldCommand.UnspawnAgent).agent)
        assertEquals(43L, appliesAt) // currentTick + 1
    }

    @Test
    fun `does not submit Unspawn for stale agents already without an active position`() {
        activity.touch(ghostAgent) // ghost is stale AND not in query.active
        clock += props.timeout.toMillis() + 20

        reaper.reap()

        assertTrue(gateway.submissions.isEmpty())
    }

    @Test
    fun `forgets stale agents from the registry regardless of active position`() {
        activity.touch(staleAgent)
        activity.touch(ghostAgent)
        clock += props.timeout.toMillis() + 20

        reaper.reap()

        // Both staleAgent and ghostAgent should be removed; nothing left to reap on a second pass
        gateway.submissions.clear()
        reaper.reap()
        assertTrue(gateway.submissions.isEmpty())
    }

    private class RecordingGateway : WorldCommandGateway {
        val submissions = mutableListOf<Pair<WorldCommand, Long>>()
        override fun submit(command: WorldCommand, appliesAtTick: Long) {
            submissions += command to appliesAtTick
        }
    }

    private class StubQuery(private val active: Set<AgentId>) : WorldQueryGateway {
        override fun locationOf(agent: AgentId): NodeId? = if (agent in active) NodeId(1L) else null
        override fun activePositionOf(agent: AgentId): NodeId? = if (agent in active) NodeId(1L) else null
        override fun node(id: NodeId): Node? = null
        override fun region(id: RegionId): Region? = null
        override fun nodesWithin(origin: NodeId, radius: Int): Set<NodeId> = emptySet()
        override fun randomSpawnableNode(): NodeId? = NodeId(1L)
        override fun starterNodeFor(race: dev.gvart.genesara.player.RaceId): NodeId? = null
        override fun bodyOf(agent: AgentId): dev.gvart.genesara.world.BodyView? = null
        override fun inventoryOf(agent: AgentId): dev.gvart.genesara.world.InventoryView =
            dev.gvart.genesara.world.InventoryView(emptyList())
        override fun resourcesAt(nodeId: NodeId, tick: Long): dev.gvart.genesara.world.NodeResources =
            dev.gvart.genesara.world.NodeResources.EMPTY
    }

    private class StubTickClock(private val currentTick: Long) : TickClock {
        override fun currentTick(): Long = currentTick
    }
}
