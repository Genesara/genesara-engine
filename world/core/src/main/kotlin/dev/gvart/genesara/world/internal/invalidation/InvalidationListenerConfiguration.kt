package dev.gvart.genesara.world.internal.invalidation

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.listener.RedisMessageListenerContainer

@Configuration
class InvalidationListenerConfiguration {

    @Bean(destroyMethod = "stop")
    fun invalidationListenerContainer(connectionFactory: RedisConnectionFactory): RedisMessageListenerContainer =
        RedisMessageListenerContainer().apply {
            setConnectionFactory(connectionFactory)
        }
}
