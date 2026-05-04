package dev.gvart.genesara.api.internal.mcp.events

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.BodyDelta
import dev.gvart.genesara.world.events.WorldEvent
import io.modelcontextprotocol.server.McpSyncServer
import io.modelcontextprotocol.spec.McpSchema.ResourcesUpdatedNotification
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * Listens for [WorldEvent]s on the Spring bus, appends a per-agent envelope into the event log,
 * and triggers `notifications/resources/updated` on `agent://{id}/events` so subscribed agents
 * pull the new entries via `resources/read?after={seq}`.
 *
 * The log is non-destructive: events stay readable across reconnects until they fall off via
 * the TTL/backlog cap. Despawn no longer discards the log — clients can read final events using
 * the cursor.
 */
@Component
internal class AgentEventDispatcher(
    private val mcpServer: McpSyncServer,
    private val log: AgentEventLog,
    private val mapper: ObjectMapper,
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @EventListener
    fun on(event: WorldEvent.AgentMoved) = publish(event.agent, "agent.moved", event)

    @EventListener
    fun on(event: WorldEvent.AgentSpawned) = publish(event.agent, "agent.spawned", event)

    @EventListener
    fun on(event: WorldEvent.AgentDespawned) = publish(event.agent, "agent.despawned", event)

    @EventListener
    fun on(event: WorldEvent.ResourceHarvested) = publish(event.agent, "resource.harvested", event)

    @EventListener
    fun on(event: WorldEvent.ItemConsumed) = publish(event.agent, "item.consumed", event)

    @EventListener
    fun on(event: WorldEvent.AgentDrank) = publish(event.agent, "agent.drank", event)

    @EventListener
    fun on(event: WorldEvent.SkillMilestoneReached) = publish(event.agent, "skill.milestone", event)

    @EventListener
    fun on(event: WorldEvent.SkillRecommended) = publish(event.agent, "skill.recommended", event)

    @EventListener
    fun on(event: WorldEvent.ItemCrafted) = publish(event.agent, "item.crafted", event)

    @EventListener
    fun on(event: WorldEvent.CommandRejected) = publish(event.agent, "command.rejected", event)

    @EventListener
    fun on(event: WorldEvent.PassivesApplied) {
        event.deltas.forEach { (agent, delta) ->
            publish(agent, "agent.passives", PassivesPayload(agent, delta, event.tick))
        }
    }

    private fun publish(agent: AgentId, type: String, payload: Any) {
        val tick = (payload as? WorldEvent)?.tick ?: (payload as? PassivesPayload)?.tick ?: 0L
        log.append(agent, type, tick, mapper.valueToTree(payload))
        val uri = "agent://${agent.id}/events"
        try {
            mcpServer.notifyResourcesUpdated(ResourcesUpdatedNotification(uri))
        } catch (e: Exception) {
            // Agent may not be subscribed (or no live session). The event remains in the log
            // and will be read on next subscribe + read.
            logger.debug("notifyResourcesUpdated for {} failed: {}", uri, e.message)
        }
    }

    internal data class PassivesPayload(
        val agent: AgentId,
        val delta: BodyDelta,
        val tick: Long,
    )
}
