package dev.gvart.genesara.world.internal.tick.lease

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Stable identity for this JVM. Used as the value side of the per-world
 * lease key so a fenced GET can reject a write from a previous lease
 * holder. Defaults to a fresh UUID on each JVM start; override via
 * `application.shard.pod-id` (or the matching `POD_ID` env var) when a
 * deployment needs a deterministic id.
 */
@Component
internal class PodIdentity(
    @Value("\${application.shard.pod-id:}") configured: String,
) {
    val id: String = configured.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
}
