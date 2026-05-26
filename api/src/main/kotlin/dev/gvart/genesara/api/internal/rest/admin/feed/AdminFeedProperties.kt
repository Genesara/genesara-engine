package dev.gvart.genesara.api.internal.rest.admin.feed

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * [backlogCap] pinned at 5000 by ADR-0004 (`docs/adr/0004-admin-tool-open-questions.md`) — 5× the
 * per-agent log's 1000 cap. Treat as tunable, not contractual.
 */
@ConfigurationProperties(prefix = "application.admin.feed")
internal data class AdminFeedProperties(
    val backlogCap: Long = 5000,
    val ttl: Duration = Duration.ofHours(1),
    val maxConnectionsPerToken: Int = 16,
)
