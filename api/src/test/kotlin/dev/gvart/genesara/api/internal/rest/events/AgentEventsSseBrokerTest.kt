package dev.gvart.genesara.api.internal.rest.events

import dev.gvart.genesara.api.internal.mcp.events.AgentEvent
import dev.gvart.genesara.api.internal.mcp.events.AgentEventLog
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.invalidation.InvalidationMessage
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.Message
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentEventsSseBrokerTest {

    private val mapper: ObjectMapper = JsonMapper.builder().addModule(kotlinModule()).build()
    private val targetAgent = AgentId(UUID.randomUUID())
    private val unrelatedAgent = AgentId(UUID.randomUUID())

    @Test
    fun `onMessage with AgentNotify for a registered agent reads the log since the emitter's last seq`() {
        val log = CountingLog()
        val broker = AgentEventsSseBroker(log, NoopContainer, mapper)
        broker.register(targetAgent, afterSeq = 0L, timeoutMs = 10_000L)

        broker.onMessage(redisMessage(InvalidationMessage.AgentNotify(targetAgent)), null)

        // One read happens at register() time, plus the one triggered by the notification.
        assertEquals(2, log.sinceCalls.get())
    }

    @Test
    fun `onMessage for an unrelated agent does not touch the log`() {
        val log = CountingLog()
        val broker = AgentEventsSseBroker(log, NoopContainer, mapper)
        broker.register(targetAgent, afterSeq = 0L, timeoutMs = 10_000L)
        log.sinceCalls.set(0)

        broker.onMessage(redisMessage(InvalidationMessage.AgentNotify(unrelatedAgent)), null)

        assertEquals(0, log.sinceCalls.get())
    }

    @Test
    fun `onMessage swallows malformed payloads without throwing`() {
        val log = CountingLog()
        val broker = AgentEventsSseBroker(log, NoopContainer, mapper)

        broker.onMessage(rawMessage("not-json".toByteArray()), null)

        assertTrue(log.calls.isEmpty(), "no log read should happen for a malformed message")
    }

    private fun redisMessage(payload: InvalidationMessage): Message =
        rawMessage(mapper.writeValueAsBytes(payload))

    private fun rawMessage(body: ByteArray): Message = object : Message {
        override fun getBody(): ByteArray = body
        override fun getChannel(): ByteArray = "genesara:invalidation".toByteArray()
    }

    private class CountingLog : AgentEventLog {
        val sinceCalls = AtomicInteger()
        val calls = ConcurrentLinkedQueue<Pair<AgentId, Long>>()
        override fun append(agent: AgentId, type: String, tick: Long, payload: JsonNode): AgentEvent =
            error("append() not used in this test")
        override fun since(agent: AgentId, after: Long): List<AgentEvent> {
            sinceCalls.incrementAndGet()
            calls += agent to after
            return emptyList()
        }
        override fun discard(agent: AgentId) {}
    }

    private object NoopContainer : RedisMessageListenerContainer()
}
