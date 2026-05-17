package dev.gvart.genesara.world.internal.invalidation

import dev.gvart.genesara.world.invalidation.InvalidationBus
import dev.gvart.genesara.world.invalidation.InvalidationMessage
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

@Component
class RedisInvalidationBus(
    private val redis: StringRedisTemplate,
    private val mapper: ObjectMapper,
) : InvalidationBus {

    override fun publish(message: InvalidationMessage) {
        redis.convertAndSend(InvalidationBus.CHANNEL, mapper.writeValueAsString(message))
    }
}
