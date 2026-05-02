package dev.gvart.genesara.api.internal.mcp.presence

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "application.presence")
internal data class PresenceProperties(
    val timeout: Duration = Duration.ofMinutes(30),
    val reaperInterval: Duration = Duration.ofMinutes(1),
    val flushInterval: Duration = Duration.ofSeconds(60),
)
