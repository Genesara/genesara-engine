package dev.gvart.genesara.api.internal.security.jwt

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "application.security.jwt")
data class JwtProperties(
    val secret: String,
    val ttl: Duration = Duration.ofHours(24),
)
