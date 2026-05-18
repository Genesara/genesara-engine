package dev.gvart.genesara.api.internal.rest.events

import dev.gvart.genesara.api.internal.mcp.events.AgentEventLog
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.invalidation.InvalidationBus
import dev.gvart.genesara.world.invalidation.InvalidationMessage
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.data.redis.connection.Message
import org.springframework.data.redis.connection.MessageListener
import org.springframework.data.redis.listener.ChannelTopic
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import tools.jackson.databind.ObjectMapper
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicLong

/** Log is the source of truth; pub/sub is a wakeup hint. A dropped notification self-heals on the next read. */
internal interface AgentEventsBroker {
    fun register(agentId: AgentId, afterSeq: Long, timeoutMs: Long): SseEmitter
}

@Component
internal class AgentEventsSseBroker(
    private val log: AgentEventLog,
    private val container: RedisMessageListenerContainer,
    private val mapper: ObjectMapper,
) : MessageListener, AgentEventsBroker {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val emitters = ConcurrentHashMap<AgentId, CopyOnWriteArraySet<Entry>>()

    @PostConstruct
    fun subscribe() {
        container.addMessageListener(this, ChannelTopic(InvalidationBus.CHANNEL))
    }

    override fun register(agentId: AgentId, afterSeq: Long, timeoutMs: Long): SseEmitter {
        val emitter = SseEmitter(timeoutMs)
        val entry = Entry(emitter, AtomicLong(afterSeq))
        val bucket = emitters.computeIfAbsent(agentId) { CopyOnWriteArraySet() }
        bucket += entry
        val cleanup = Runnable {
            bucket.remove(entry)
            if (bucket.isEmpty()) emitters.remove(agentId, bucket)
        }
        emitter.onCompletion(cleanup)
        emitter.onTimeout {
            emitter.complete()
            cleanup.run()
        }
        emitter.onError {
            cleanup.run()
        }
        dispatch(agentId, entry)
        return emitter
    }

    override fun onMessage(message: Message, pattern: ByteArray?) {
        val parsed = try {
            mapper.readValue(message.body, InvalidationMessage::class.java)
        } catch (t: Throwable) {
            logger.warn("Failed to deserialize invalidation message: {}", t.message)
            return
        }
        if (parsed !is InvalidationMessage.AgentNotify) return
        emitters[parsed.agentId]?.forEach { dispatch(parsed.agentId, it) }
    }

    /**
     * Keep emitters warm through proxies with idle timeouts (AWS ALB / nginx default 60s).
     * SSE comment frames (`:\n\n`) are ignored by EventSource clients but reset the proxy
     * idle clock.
     */
    @Scheduled(fixedDelayString = "\${application.dashboard.sse.heartbeat:PT30S}")
    fun heartbeat() {
        emitters.forEach { (_, bucket) ->
            bucket.forEach { entry ->
                try {
                    entry.emitter.send(SseEmitter.event().comment("ping"))
                } catch (_: Throwable) {
                    try { entry.emitter.complete() } catch (_: Throwable) { /* already complete */ }
                }
            }
        }
    }

    private fun dispatch(agentId: AgentId, entry: Entry) {
        val events = log.since(agentId, entry.lastSeq.get())
        if (events.isEmpty()) return
        for (event in events) {
            try {
                entry.emitter.send(
                    SseEmitter.event()
                        .id(event.seq.toString())
                        .name(event.type)
                        .data(event),
                )
                entry.lastSeq.set(event.seq)
            } catch (t: Throwable) {
                // Best-effort: a dropped frame closes the emitter; the cleanup hook removes the entry.
                logger.debug("SSE send failed for agent={} seq={}: {}", agentId.id, event.seq, t.message)
                try {
                    entry.emitter.completeWithError(t)
                } catch (_: Throwable) { /* already complete */ }
                return
            }
        }
    }

    private data class Entry(val emitter: SseEmitter, val lastSeq: AtomicLong)
}
