package dev.gvart.genesara.api.internal.rest.admin.feed

import dev.gvart.genesara.player.events.AgentEvent
import dev.gvart.genesara.world.events.WorldEvent
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Component
internal class AdminFeedDispatcher(
    private val log: AdminFeedLog,
    private val redis: StringRedisTemplate,
    private val mapper: ObjectMapper,
) {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val typeNameCache = ConcurrentHashMap<Class<*>, String>()

    @EventListener
    fun onWorld(event: WorldEvent) = publish(event, event.tick)

    @EventListener
    fun onAgent(event: AgentEvent) = publish(event, event.tick)

    private fun publish(event: Any, tick: Long) {
        val payload = mapper.valueToTree<JsonNode>(event)
        val appended = log.append(
            type = typeOf(event::class.java),
            tick = tick,
            agent = extractAgent(payload),
            node = extractNode(payload),
            payload = payload,
        )
        runCatching { redis.convertAndSend(RedisAdminFeedLog.NOTIFY_CHANNEL, appended.seq.toString()) }
            .onFailure { logger.debug("publish to {} failed: {}", RedisAdminFeedLog.NOTIFY_CHANNEL, it.message) }
    }

    private fun typeOf(clazz: Class<*>): String =
        typeNameCache.computeIfAbsent(clazz) { camelToSnakeFirstDot(it.simpleName) }

    private fun extractAgent(payload: JsonNode): UUID? {
        val agentNode = payload.path("agent")
        val raw = when {
            agentNode.isString -> agentNode.asString()
            agentNode.isObject -> agentNode.path("id").asString(null)
            else -> null
        } ?: return null
        return runCatching { UUID.fromString(raw) }.getOrNull()
    }

    private fun extractNode(payload: JsonNode): Long? {
        for (field in NODE_FIELDS) {
            val node = payload.path(field)
            if (node.isMissingNode || node.isNull) continue
            if (node.isIntegralNumber) return node.asLong()
            val inner = node.path("value")
            if (inner.isIntegralNumber) return inner.asLong()
        }
        return null
    }

    companion object {
        private val NODE_FIELDS = listOf("at", "to", "node")

        internal fun camelToSnakeFirstDot(simpleName: String): String {
            val builder = StringBuilder(simpleName.length + 4)
            var dotted = false
            simpleName.forEachIndexed { i, ch ->
                if (i > 0 && ch.isUpperCase()) {
                    if (!dotted) {
                        builder.append('.')
                        dotted = true
                    } else {
                        builder.append('_')
                    }
                }
                builder.append(ch.lowercaseChar())
            }
            return builder.toString()
        }
    }
}
