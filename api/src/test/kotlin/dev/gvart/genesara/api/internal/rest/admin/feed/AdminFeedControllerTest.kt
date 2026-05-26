package dev.gvart.genesara.api.internal.rest.admin.feed

import dev.gvart.genesara.api.internal.rest.GlobalExceptionAdvice
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.util.UUID
import kotlin.test.assertEquals

class AdminFeedControllerTest {

    private val mapper = JsonMapper.builder().addModule(kotlinModule()).build()
    private lateinit var log: StubFeedLog
    private lateinit var broker: StubBroker
    private lateinit var mvc: MockMvc

    @BeforeEach
    fun setup() {
        log = StubFeedLog()
        broker = StubBroker()
        mvc = MockMvcBuilders.standaloneSetup(AdminFeedController(log, broker))
            .setControllerAdvice(GlobalExceptionAdvice())
            .build()
    }

    @Test
    fun `range returns entries from log when bounds are valid`() {
        log.rangeResult = listOf(adminFeedEvent(seq = 2L, type = "agent.moved"))

        mvc.get("/admin/feed/range?from=1&to=5") {
            header("Authorization", "Bearer t")
        }.andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].seq") { value(2) }
            jsonPath("$[0].type") { value("agent.moved") }
        }
        assertEquals(1L to 5L, log.lastRangeBounds)
    }

    @Test
    fun `range rejects negative from with 400`() {
        mvc.get("/admin/feed/range?from=-1&to=5") {
            header("Authorization", "Bearer t")
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.detail") { value("from must be >= 0") }
        }
    }

    @Test
    fun `range rejects to less than from with 400`() {
        mvc.get("/admin/feed/range?from=5&to=2") {
            header("Authorization", "Bearer t")
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.detail") { value("to must be >= from") }
        }
    }

    @Test
    fun `range parses csv type and uuid agent filters`() {
        val agentUuid = UUID.randomUUID()
        log.rangeResult = emptyList()

        mvc.get("/admin/feed/range?from=0&to=10&type=agent.moved,agent.spawned&agent=$agentUuid&node=42") {
            header("Authorization", "Bearer t")
        }.andExpect { status { isOk() } }

        val seenFilter = log.lastFilter!!
        assertEquals(setOf("agent.moved", "agent.spawned"), seenFilter.types)
        assertEquals(agentUuid, seenFilter.agent)
        assertEquals(42L, seenFilter.node)
    }

    @Test
    fun `range rejects malformed agent uuid with 400`() {
        mvc.get("/admin/feed/range?from=0&to=10&agent=not-a-uuid") {
            header("Authorization", "Bearer t")
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.detail") { value("agent must be a UUID") }
        }
    }

    @Test
    fun `stream rejects negative after with 400`() {
        mvc.get("/admin/feed?after=-1") {
            header("Authorization", "Bearer t")
        }.andExpect {
            status { isBadRequest() }
        }
    }

    @Test
    fun `stream rejects missing Authorization header with 401`() {
        mvc.get("/admin/feed").andExpect {
            status { isUnauthorized() }
        }
    }

    @Test
    fun `stream propagates 429 from broker register`() {
        broker.failWith = ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "cap reached")

        mvc.get("/admin/feed") {
            header("Authorization", "Bearer t")
        }.andExpect {
            status { isTooManyRequests() }
            jsonPath("$.detail") { value("cap reached") }
        }
    }

    private fun adminFeedEvent(seq: Long, type: String): AdminFeedEvent = AdminFeedEvent(
        id = UUID.randomUUID(),
        seq = seq,
        type = type,
        tick = 0L,
        agent = null,
        node = null,
        payload = mapper.createObjectNode(),
    )

    private class StubFeedLog : AdminFeedLog {
        var rangeResult: List<AdminFeedEvent> = emptyList()
        var lastRangeBounds: Pair<Long, Long>? = null
        var lastFilter: AdminFeedFilter? = null

        override fun append(
            type: String,
            tick: Long,
            agent: UUID?,
            node: Long?,
            payload: JsonNode,
        ): AdminFeedEvent = error("append unused in controller tests")

        override fun since(after: Long, filter: AdminFeedFilter): List<AdminFeedEvent> = emptyList()

        override fun range(from: Long, to: Long, filter: AdminFeedFilter): List<AdminFeedEvent> {
            lastRangeBounds = from to to
            lastFilter = filter
            return rangeResult
        }
    }

    private class StubBroker : AdminFeedBroker {
        var failWith: ResponseStatusException? = null

        override fun register(
            token: String,
            afterSeq: Long,
            filter: AdminFeedFilter,
            timeoutMs: Long,
        ): SseEmitter {
            failWith?.let { throw it }
            return SseEmitter(timeoutMs)
        }
    }
}
