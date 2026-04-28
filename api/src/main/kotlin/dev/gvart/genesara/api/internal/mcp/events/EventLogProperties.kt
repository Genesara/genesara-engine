package dev.gvart.genesara.api.internal.mcp.events

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "application.events")
internal data class EventLogProperties(
    val backlogCap: Long = 500,
    val ttl: Duration = Duration.ofHours(1),
)
