package dev.gvart.genesara.api.internal.mcp.events

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.world.Gauge
import dev.gvart.genesara.world.ItemId
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
    fun `command-outcome events are forwarded to the agent's stream with causedBy intact`() {
        // The Drink, Gather, and Consume verbs all produce per-command outcome events. They
        // must reach the agent's stream so the agent can correlate them with the originating
        // commandId. A regression here would silently break the ack/event protocol.
        val gatherCmd = UUID.randomUUID()
        val consumeCmd = UUID.randomUUID()
        val drinkCmd = UUID.randomUUID()

        dispatcher.on(WorldEvent.ResourceGathered(agent, NodeId(1L), ItemId("WOOD"), quantity = 1, tick = 1, causedBy = gatherCmd))
        dispatcher.on(WorldEvent.ItemConsumed(agent, ItemId("BERRY"), Gauge.HUNGER, refilled = 20, tick = 2, causedBy = consumeCmd))
        dispatcher.on(WorldEvent.AgentDrank(agent, NodeId(1L), refilled = 25, tick = 3, causedBy = drinkCmd))

        val all = log.since(agent, 0)
        assertEquals(listOf("resource.gathered", "item.consumed", "agent.drank"), all.map { it.type })
        assertEquals(gatherCmd.toString(), all[0].payload.get("causedBy").asString())
        assertEquals(consumeCmd.toString(), all[1].payload.get("causedBy").asString())
        assertEquals(drinkCmd.toString(), all[2].payload.get("causedBy").asString())
    }

    @Test
    fun `SkillMilestoneReached and SkillRecommended events reach the agent's stream`() {
        val cmdId = UUID.randomUUID()
        val recCmdId = UUID.randomUUID()
        dispatcher.on(
            WorldEvent.SkillMilestoneReached(
                agent = agent,
                skill = SkillId("FORAGING"),
                milestone = 50,
                tick = 5,
                causedBy = cmdId,
            ),
        )
        dispatcher.on(
            WorldEvent.SkillRecommended(
                agent = agent,
                skill = SkillId("MINING"),
                recommendCount = 1,
                slotsFree = 8,
                tick = 6,
                causedBy = recCmdId,
            ),
        )

        val all = log.since(agent, 0)
        assertEquals(listOf("skill.milestone", "skill.recommended"), all.map { it.type })
        // The whole payload should at minimum carry the milestone fields. Asserting on
        // the structural shape is fragile across Jackson versions for value classes; the
        // important contract is that the event lands and types are right.
        assertEquals(50, all[0].payload.get("milestone").asInt())
        assertEquals(1, all[1].payload.get("recommendCount").asInt())
        assertEquals(8, all[1].payload.get("slotsFree").asInt())
        // Skill payload is non-null in both — exact serialised form is left to Jackson.
        kotlin.test.assertNotNull(all[0].payload.get("skill"))
        kotlin.test.assertNotNull(all[1].payload.get("skill"))
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
