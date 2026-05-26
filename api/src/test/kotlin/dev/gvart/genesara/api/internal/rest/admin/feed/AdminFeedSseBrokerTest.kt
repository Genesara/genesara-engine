package dev.gvart.genesara.api.internal.rest.admin.feed

import org.springframework.data.redis.listener.RedisMessageListenerContainer
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.JsonNode
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AdminFeedSseBrokerTest {

    private val props = AdminFeedProperties(
        backlogCap = 5000,
        ttl = Duration.ofMinutes(1),
        maxConnectionsPerToken = 16,
    )
    private val log = EmptyAdminFeedLog()
    private val broker = AdminFeedSseBroker(log, NoopContainer, props)

    @Test
    fun `17th concurrent connection for the same token is rejected with 429`() {
        val token = "admin-token"

        repeat(16) {
            broker.register(token, afterSeq = 0L, filter = AdminFeedFilter.NONE, timeoutMs = 60_000L)
        }
        assertEquals(16, broker.connectionCount(token))

        val ex = assertFailsWith<ResponseStatusException> {
            broker.register(token, afterSeq = 0L, filter = AdminFeedFilter.NONE, timeoutMs = 60_000L)
        }
        assertEquals(429, ex.statusCode.value())
        assertEquals(16, broker.connectionCount(token))
    }

    @Test
    fun `different tokens have independent caps`() {
        val tokenA = "token-a"
        val tokenB = "token-b"

        repeat(16) { broker.register(tokenA, 0L, AdminFeedFilter.NONE, 60_000L) }
        repeat(16) { broker.register(tokenB, 0L, AdminFeedFilter.NONE, 60_000L) }

        assertEquals(16, broker.connectionCount(tokenA))
        assertEquals(16, broker.connectionCount(tokenB))
    }

    private class EmptyAdminFeedLog : AdminFeedLog {
        override fun append(
            type: String,
            tick: Long,
            agent: UUID?,
            node: Long?,
            payload: JsonNode,
        ): AdminFeedEvent = error("append unused in broker tests")

        override fun since(after: Long, filter: AdminFeedFilter): List<AdminFeedEvent> = emptyList()
        override fun range(from: Long, to: Long, filter: AdminFeedFilter): List<AdminFeedEvent> = emptyList()
    }

    private object NoopContainer : RedisMessageListenerContainer()
}
