package dev.gvart.genesara.api.internal.mcp.events

import dev.gvart.genesara.player.AgentId
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * Per-agent event log backing `agent://{id}/events`.
 *
 * Reads are **non-destructive** — clients pass the last-seen [AgentEvent.seq] as `after` and the
 * log returns everything strictly after that sequence. Each [append] allocates a fresh monotonic
 * `seq` so clients can resume a read after a brief disconnect without losing events. Old entries
 * are evicted by TTL and a backlog cap rather than by drain.
 */
internal interface AgentEventLog {
    /** Appends a new event for [agent], stamping it with a fresh monotonic [AgentEvent.seq]. */
    fun append(agent: AgentId, type: String, tick: Long, payload: JsonNode): AgentEvent

    /** Returns all events for [agent] whose `seq > after`, in append order. */
    fun since(agent: AgentId, after: Long): List<AgentEvent>

    /** Drops the entire log and seq counter for [agent]. Use only for explicit cleanup paths. */
    fun discard(agent: AgentId)
}

@Component
internal class RedisAgentEventLog(
    private val redis: StringRedisTemplate,
    private val mapper: ObjectMapper,
    private val props: EventLogProperties,
) : AgentEventLog {

    override fun append(agent: AgentId, type: String, tick: Long, payload: JsonNode): AgentEvent {
        val seq = redis.opsForValue().increment(seqKey(agent))
            ?: error("Redis INCR returned null for ${seqKey(agent)}")
        val event = AgentEvent(id = java.util.UUID.randomUUID(), seq = seq, type = type, tick = tick, payload = payload)
        val raw = mapper.writeValueAsString(event)
        val listKey = key(agent)
        redis.opsForList().rightPush(listKey, raw)
        redis.opsForList().trim(listKey, -props.backlogCap, -1)
        redis.expire(listKey, props.ttl)
        redis.expire(seqKey(agent), props.ttl)
        return event
    }

    override fun since(agent: AgentId, after: Long): List<AgentEvent> {
        val raw = redis.opsForList().range(key(agent), 0, -1).orEmpty()
        return raw.asSequence()
            .map { mapper.readValue(it, AgentEvent::class.java) }
            .filter { it.seq > after }
            .toList()
    }

    override fun discard(agent: AgentId) {
        redis.delete(key(agent))
        redis.delete(seqKey(agent))
    }

    private fun key(agent: AgentId) = "agent:${agent.id}:events"
    private fun seqKey(agent: AgentId) = "agent:${agent.id}:event-seq"
}
