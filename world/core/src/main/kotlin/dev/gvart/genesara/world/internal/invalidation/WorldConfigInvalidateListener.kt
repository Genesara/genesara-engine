package dev.gvart.genesara.world.internal.invalidation

import dev.gvart.genesara.world.internal.tick.lease.LeasedWorlds
import dev.gvart.genesara.world.internal.vision.VisionBlockerCache
import dev.gvart.genesara.world.internal.worldstate.WorldStaticConfig
import dev.gvart.genesara.world.invalidation.InvalidationBus
import dev.gvart.genesara.world.invalidation.InvalidationMessage
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.data.redis.connection.Message
import org.springframework.data.redis.connection.MessageListener
import org.springframework.data.redis.listener.ChannelTopic
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

@Component
internal class WorldConfigInvalidateListener(
    private val container: RedisMessageListenerContainer,
    private val mapper: ObjectMapper,
    private val leasedWorlds: LeasedWorlds,
    private val staticConfig: WorldStaticConfig,
    private val visionBlockers: VisionBlockerCache,
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
        if (parsed !is InvalidationMessage.WorldConfigInvalidate) return
        // WorldStaticConfig.reload() is global, not per-world — gating on "this pod
        // leases at least one world" matches the actual blast radius. Pods with no
        // leases skip the DB roundtrip; lease-acquire is responsible for getting
        // fresh config when a world is later picked up.
        if (leasedWorlds.held().isEmpty()) return
        try {
            staticConfig.reload()
            log.info("Reloaded WorldStaticConfig in response to invalidation for world={}", parsed.worldId.value)
        } catch (t: Throwable) {
            log.warn("WorldStaticConfig.reload() failed for world={}: {}", parsed.worldId.value, t.message)
        }
        try {
            visionBlockers.seedAll()
        } catch (t: Throwable) {
            log.warn("vision-blocker re-seed failed after reload: {}", t.message)
        }
    }
}
