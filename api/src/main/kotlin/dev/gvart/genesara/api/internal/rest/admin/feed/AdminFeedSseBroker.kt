package dev.gvart.genesara.api.internal.rest.admin.feed

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.data.redis.connection.Message
import org.springframework.data.redis.connection.MessageListener
import org.springframework.data.redis.listener.ChannelTopic
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import org.springframework.http.HttpStatus
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicLong

internal interface AdminFeedBroker {
    /**
     * @throws ResponseStatusException 429 if this token already holds the per-token cap of concurrent
     *   connections (pinned by [AdminFeedProperties.maxConnectionsPerToken]).
     */
    fun register(token: String, afterSeq: Long, filter: AdminFeedFilter, timeoutMs: Long): SseEmitter
}

@Component
internal class AdminFeedSseBroker(
    private val log: AdminFeedLog,
    private val container: RedisMessageListenerContainer,
    private val props: AdminFeedProperties,
) : MessageListener, AdminFeedBroker {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val emitters = ConcurrentHashMap<String, CopyOnWriteArraySet<Entry>>()

    @PostConstruct
    fun subscribe() {
        container.addMessageListener(this, ChannelTopic(RedisAdminFeedLog.NOTIFY_CHANNEL))
    }

    override fun register(
        token: String,
        afterSeq: Long,
        filter: AdminFeedFilter,
        timeoutMs: Long,
    ): SseEmitter = registerEmitter(token, SseEmitter(timeoutMs), afterSeq, filter)

    internal fun registerEmitter(
        token: String,
        emitter: SseEmitter,
        afterSeq: Long,
        filter: AdminFeedFilter,
    ): SseEmitter {
        val bucket = emitters.computeIfAbsent(token) { CopyOnWriteArraySet() }
        val entry = Entry(emitter, AtomicLong(afterSeq), filter)
        synchronized(bucket) {
            if (bucket.size >= props.maxConnectionsPerToken) {
                throw ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Admin feed connection cap reached (${props.maxConnectionsPerToken} per token)",
                )
            }
            bucket += entry
        }
        val cleanup = Runnable {
            bucket.remove(entry)
            if (bucket.isEmpty()) emitters.remove(token, bucket)
        }
        emitter.onCompletion(cleanup)
        emitter.onTimeout {
            emitter.complete()
            cleanup.run()
        }
        emitter.onError {
            cleanup.run()
        }
        dispatch(entry)
        return emitter
    }

    override fun onMessage(message: Message, pattern: ByteArray?) {
        emitters.values.forEach { bucket -> bucket.forEach { dispatch(it) } }
    }

    @Scheduled(fixedDelayString = "\${application.admin.feed.heartbeat:PT30S}")
    fun heartbeat() {
        emitters.values.forEach { bucket ->
            bucket.forEach { entry ->
                runCatching { entry.emitter.send(SseEmitter.event().comment("ping")) }
                    .onFailure { runCatching { entry.emitter.complete() } }
            }
        }
    }

    internal fun connectionCount(token: String): Int = emitters[token]?.size ?: 0

    private fun dispatch(entry: Entry) {
        synchronized(entry) {
            val events = log.since(entry.lastSeq.get(), entry.filter)
            if (events.isEmpty()) return
            for (event in events) {
                val sent = runCatching {
                    entry.emitter.send(
                        SseEmitter.event().id(event.seq.toString()).name(event.type).data(event),
                    )
                }
                if (sent.isFailure) {
                    val cause = sent.exceptionOrNull()
                    logger.debug("admin feed SSE send failed for seq={}: {}", event.seq, cause?.message)
                    runCatching { entry.emitter.completeWithError(cause!!) }
                    return
                }
                entry.lastSeq.set(event.seq)
            }
        }
    }

    private data class Entry(
        val emitter: SseEmitter,
        val lastSeq: AtomicLong,
        val filter: AdminFeedFilter,
    )
}
