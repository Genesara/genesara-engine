package dev.gvart.genesara.world.invalidation

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.WorldId

/**
 * Each `@JsonSubTypes.Type.name` is the wire-format discriminator on the cross-pod
 * pub/sub channel. Stable contract: renaming a Kotlin class is fine, changing a
 * discriminator silently breaks deserialization on the receiving pod.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@type")
@JsonSubTypes(
    JsonSubTypes.Type(value = InvalidationMessage.AgentNotify::class, name = "agent-notify"),
    JsonSubTypes.Type(value = InvalidationMessage.WorldConfigInvalidate::class, name = "world-config-invalidate"),
)
sealed interface InvalidationMessage {

    /** Wakeup signal for agent's MCP event-resource subscribers, regardless of which pod holds the live session. */
    data class AgentNotify(val agentId: AgentId) : InvalidationMessage

    /** Static-config (region/node graph) for [worldId] has changed; lease holders for that world should reload. */
    data class WorldConfigInvalidate(val worldId: WorldId) : InvalidationMessage
}
