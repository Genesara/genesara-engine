package dev.gvart.genesara.api.internal.mcp.events

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.events.WorldEvent
import io.modelcontextprotocol.server.McpSyncServer
import io.modelcontextprotocol.spec.McpSchema.ResourcesUpdatedNotification
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.util.UUID
import kotlin.test.assertEquals

class AgentEventDispatcherTest {

    private val mapper = JsonMapper.builder().addModule(kotlinModule()).build()
    private val mcp: McpSyncServer = mock(McpSyncServer::class.java)
    private val log = FakeAgentEventLog()
    private val dispatcher = AgentEventDispatcher(mcp, log, mapper)

    private val agent = AgentId(UUID.randomUUID())

    @Test
    fun `AgentMoved appends an envelope and pings the per-agent resource URI`() {
        val cmdId = UUID.randomUUID()
        val event = WorldEvent.AgentMoved(agent, NodeId(1L), NodeId(2L), tick = 5, causedBy = cmdId)

        dispatcher.on(event)

        val all = log.since(agent, 0)
        assertEquals(1, all.size)
        val envelope = all.single()
        assertEquals("agent.moved", envelope.type)
        assertEquals(5L, envelope.tick)
        assertEquals(1L, envelope.seq)
        assertEquals(cmdId.toString(), envelope.payload.get("causedBy").asString())

        val captor = ArgumentCaptor.forClass(ResourcesUpdatedNotification::class.java)
        verify(mcp).notifyResourcesUpdated(captor.capture())
        assertEquals("agent://${agent.id}/events", captor.value.uri())
    }

    @Test
    fun `AgentDespawned publishes the event and keeps the log readable for cursor-based replay`() {
        val cmdId = UUID.randomUUID()
        // Pre-existing event from before the despawn
        val pre = log.append(agent, "agent.moved", 1L, mapper.createObjectNode())

        dispatcher.on(WorldEvent.AgentDespawned(agent, NodeId(7L), tick = 9, causedBy = cmdId))

        // Both events remain in the log so a slightly-late client can still drain via cursor.
        val all = log.since(agent, 0)
        assertEquals(2, all.size)
        assertEquals("agent.moved", all[0].type)
        assertEquals("agent.despawned", all[1].type)
        // Reading after the pre-despawn event yields only the despawned envelope.
        val tail = log.since(agent, pre.seq)
        assertEquals(1, tail.size)
        assertEquals("agent.despawned", tail.single().type)
    }

    @Test
    fun `monotonic seq across appends`() {
        repeat(3) { i ->
            dispatcher.on(WorldEvent.AgentMoved(agent, NodeId(1L), NodeId(2L), tick = i.toLong(), causedBy = UUID.randomUUID()))
        }
        val seqs = log.since(agent, 0).map { it.seq }
        assertEquals(listOf(1L, 2L, 3L), seqs)
    }

    @Test
    fun `PassivesApplied fans out one envelope per affected agent`() {
        val a1 = AgentId(UUID.randomUUID())
        val a2 = AgentId(UUID.randomUUID())
        val event = WorldEvent.PassivesApplied(
            deltas = mapOf(
                a1 to dev.gvart.genesara.world.BodyDelta(stamina = 1),
                a2 to dev.gvart.genesara.world.BodyDelta(hp = 2),
            ),
            tick = 3L,
        )

        dispatcher.on(event)

        assertEquals(1, log.since(a1, 0).size)
        assertEquals(1, log.since(a2, 0).size)
        verify(mcp).notifyResourcesUpdated(eq(ResourcesUpdatedNotification("agent://${a1.id}/events")))
        verify(mcp).notifyResourcesUpdated(eq(ResourcesUpdatedNotification("agent://${a2.id}/events")))
    }
}
