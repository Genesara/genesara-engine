package dev.gvart.genesara.api.internal.rest.admin.feed

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/** God-view feed backing `GET /admin/feed` — bounded Redis list, monotonic seq, non-destructive reads. */
internal interface AdminFeedLog {
    /** Appends one entry, stamping it with a fresh monotonic [AdminFeedEvent.seq]. */
    fun append(type: String, tick: Long, agent: UUID?, node: Long?, payload: JsonNode): AdminFeedEvent

    /** Returns entries with `seq > after` matching [filter], in append order. */
    fun since(after: Long, filter: AdminFeedFilter): List<AdminFeedEvent>

    /** Returns entries with `from <= seq <= to` matching [filter], in append order. */
    fun range(from: Long, to: Long, filter: AdminFeedFilter): List<AdminFeedEvent>
}

@Component
internal class RedisAdminFeedLog(
    private val redis: StringRedisTemplate,
    private val mapper: ObjectMapper,
    private val props: AdminFeedProperties,
) : AdminFeedLog {

    override fun append(
        type: String,
        tick: Long,
        agent: UUID?,
        node: Long?,
        payload: JsonNode,
    ): AdminFeedEvent {
        val seq = redis.opsForValue().increment(SEQ_KEY)
            ?: error("Redis INCR returned null for $SEQ_KEY")
        val event = AdminFeedEvent(
            id = UUID.randomUUID(),
            seq = seq,
            type = type,
            tick = tick,
            agent = agent,
            node = node,
            payload = payload,
        )
        val raw = mapper.writeValueAsString(event)
        redis.opsForList().rightPush(LIST_KEY, raw)
        redis.opsForList().trim(LIST_KEY, -props.backlogCap, -1)
        redis.expire(LIST_KEY, props.ttl)
        redis.expire(SEQ_KEY, props.ttl)
        return event
    }

    override fun since(after: Long, filter: AdminFeedFilter): List<AdminFeedEvent> =
        readAll().filter { it.seq > after && filter.matches(it) }

    override fun range(from: Long, to: Long, filter: AdminFeedFilter): List<AdminFeedEvent> =
        readAll().filter { it.seq in from..to && filter.matches(it) }

    private fun readAll(): List<AdminFeedEvent> {
        val raw = redis.opsForList().range(LIST_KEY, 0, -1).orEmpty()
        return raw.map { mapper.readValue(it, AdminFeedEvent::class.java) }
    }

    companion object {
        const val LIST_KEY = "admin:feed"
        const val SEQ_KEY = "admin:feed:seq"
        const val NOTIFY_CHANNEL = "admin:feed:notify"
    }
}
