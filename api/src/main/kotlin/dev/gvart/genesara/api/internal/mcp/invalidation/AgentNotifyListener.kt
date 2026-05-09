package dev.gvart.genesara.api.internal.mcp.invalidation

import dev.gvart.genesara.world.invalidation.InvalidationBus
import dev.gvart.genesara.world.invalidation.InvalidationMessage
import io.modelcontextprotocol.server.McpSyncServer
import io.modelcontextprotocol.spec.McpSchema.ResourcesUpdatedNotification
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.data.redis.connection.Message
import org.springframework.data.redis.connection.MessageListener
import org.springframework.data.redis.listener.ChannelTopic
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * Pod-local fan-out for [InvalidationMessage.AgentNotify]: every pod fires
 * `notifyResourcesUpdated` on its own MCP server. The MCP protocol's per-server
 * subscription set then filters out pods without a live session for the agent —
 * only the pod hosting the agent's MCP connection actually pushes downstream.
 */
@Component
internal class AgentNotifyListener(
    private val container: RedisMessageListenerContainer,
    private val mapper: ObjectMapper,
    private val mcpServer: McpSyncServer,
) : MessageListener {

    private val log = LoggerFactory.getLogger(javaClass)

    @PostConstruct
    fun subscribe() {
        container.addMessageListener(this, ChannelTopic(InvalidationBus.CHANNEL))
    }

    override fun onMessage(message: Message, pattern: ByteArray?) {
        val parsed = try {
            mapper.readValue(message.body, InvalidationMessage::class.java)
        } catch (t: Throwable) {
            log.warn("Failed to deserialize invalidation message: {}", t.message)
            return
        }
        if (parsed !is InvalidationMessage.AgentNotify) return
        val uri = "agent://${parsed.agentId.id}/events"
        try {
            mcpServer.notifyResourcesUpdated(ResourcesUpdatedNotification(uri))
        } catch (t: Throwable) {
            log.debug("notifyResourcesUpdated for {} failed: {}", uri, t.message)
        }
    }

}
