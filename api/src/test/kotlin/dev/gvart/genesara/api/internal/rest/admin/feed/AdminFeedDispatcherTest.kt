package dev.gvart.genesara.api.internal.rest.admin.feed

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.events.CombatEvent
import dev.gvart.genesara.world.events.CoreEvent
import org.mockito.Mockito.mock
import org.springframework.data.redis.core.StringRedisTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AdminFeedDispatcherTest {

    private val mapper: ObjectMapper = JsonMapper.builder().addModule(kotlinModule()).build()
    private val redis: StringRedisTemplate = mock(StringRedisTemplate::class.java)
    private val log = InMemoryAdminFeedLog()
    private val dispatcher = AdminFeedDispatcher(log, redis, mapper)

    private val agent = AgentId(UUID.randomUUID())
    private val cmd = UUID.randomUUID()

    @Test
    fun `AgentSpawned is appended with type agent_spawned and the node id at the time of spawn`() {
        dispatcher.onWorld(CoreEvent.AgentSpawned(agent, NodeId(42L), tick = 1L, causedBy = cmd))

        val event = log.appended.single()
        assertEquals("agent.spawned", event.type)
        assertEquals(1L, event.tick)
        assertEquals(agent.id, event.agentArg)
        assertEquals(42L, event.nodeArg)
    }

    @Test
    fun `AgentMoved extracts the destination node from the to field`() {
        dispatcher.onWorld(
            CoreEvent.AgentMoved(
                agent = agent,
                from = NodeId(1L),
                to = NodeId(2L),
                staminaSpent = 1,
                tick = 3L,
                causedBy = cmd,
            ),
        )

        val event = log.appended.single()
        assertEquals("agent.moved", event.type)
        assertEquals(2L, event.nodeArg)
        assertEquals(agent.id, event.agentArg)
    }

    @Test
    fun `events without an agent field append a null agent facet but still capture the node from at`() {
        dispatcher.onWorld(
            CombatEvent.AgentAttacked(
                attacker = AgentId(UUID.randomUUID()),
                target = AgentId(UUID.randomUUID()),
                at = NodeId(99L),
                damageType = dev.gvart.genesara.world.DamageType.SLASH,
                baseDamage = 1,
                hpLost = 1,
                isCrit = false,
                isDodged = false,
                targetHpAfter = 9,
                targetKilled = false,
                tick = 7L,
                causedBy = cmd,
            ),
        )

        val event = log.appended.single()
        assertEquals("agent.attacked", event.type)
        assertNull(event.agentArg)
        assertEquals(99L, event.nodeArg)
    }

    @Test
    fun `camelToSnakeFirstDot converts multi-word class names with underscores after the first dot`() {
        assertEquals("agent.spawned", AdminFeedDispatcher.camelToSnakeFirstDot("AgentSpawned"))
        assertEquals("agent.moved", AdminFeedDispatcher.camelToSnakeFirstDot("AgentMoved"))
        assertEquals("agent.attacked_npc", AdminFeedDispatcher.camelToSnakeFirstDot("AgentAttackedNpc"))
        assertEquals("command.rejected", AdminFeedDispatcher.camelToSnakeFirstDot("CommandRejected"))
    }

    private data class AppendCall(
        val type: String,
        val tick: Long,
        val agentArg: UUID?,
        val nodeArg: Long?,
        val payload: JsonNode,
    )

    private class InMemoryAdminFeedLog : AdminFeedLog {
        val appended = mutableListOf<AppendCall>()

        override fun append(
            type: String,
            tick: Long,
            agent: UUID?,
            node: Long?,
            payload: JsonNode,
        ): AdminFeedEvent {
            appended += AppendCall(type, tick, agent, node, payload)
            return AdminFeedEvent(
                id = UUID.randomUUID(),
                seq = appended.size.toLong(),
                type = type,
                tick = tick,
                agent = agent,
                node = node,
                payload = payload,
            )
        }

        override fun since(after: Long, filter: AdminFeedFilter): List<AdminFeedEvent> = emptyList()

        override fun range(from: Long, to: Long, filter: AdminFeedFilter): List<AdminFeedEvent> = emptyList()
    }
}
