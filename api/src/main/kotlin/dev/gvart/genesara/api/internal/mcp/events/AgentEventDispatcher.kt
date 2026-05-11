package dev.gvart.genesara.api.internal.mcp.events

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.events.AgentEvent
import dev.gvart.genesara.world.BodyDelta
import dev.gvart.genesara.world.events.WorldEvent
import dev.gvart.genesara.world.invalidation.InvalidationBus
import dev.gvart.genesara.world.invalidation.InvalidationMessage
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * Listens for [WorldEvent]s and [AgentEvent]s on the Spring bus, appends a per-agent envelope into the event log,
 * and triggers `notifications/resources/updated` on `agent://{id}/events` so subscribed agents
 * pull the new entries via `resources/read?after={seq}`.
 *
 * The log is non-destructive: events stay readable across reconnects until they fall off via
 * the TTL/backlog cap. Despawn no longer discards the log — clients can read final events using
 * the cursor.
 */
@Component
internal class AgentEventDispatcher(
    private val bus: InvalidationBus,
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
    fun on(event: AgentEvent.SkillMilestoneReached) = publish(event.agent, "skill.milestone", event)

    @EventListener
    fun on(event: AgentEvent.SkillRecommended) = publish(event.agent, "skill.recommended", event)

    @EventListener
    fun on(event: AgentEvent.AttributeMilestoneReached) = publish(event.agent, "attribute.milestone", event)

    @EventListener
    fun on(event: AgentEvent.PerkChoiceOffered) = publish(event.agent, "perk.offered", event)

    @EventListener
    fun on(event: AgentEvent.PerkChosen) = publish(event.agent, "perk.chosen", event)

    @EventListener
    fun on(event: AgentEvent.ClassChoiceOffered) = publish(event.agent, "class.offered", event)

    @EventListener
    fun on(event: AgentEvent.ClassChosen) = publish(event.agent, "class.chosen", event)

    @EventListener
    fun on(event: AgentEvent.EvolutionChoiceOffered) = publish(event.agent, "evolution.offered", event)

    @EventListener
    fun on(event: AgentEvent.ClassEvolved) = publish(event.agent, "class.evolved", event)

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

    @EventListener
    fun on(event: WorldEvent.AgentAttacked) {
        publish(event.attacker, "agent.attacked", event)
        if (event.attacker != event.target) publish(event.target, "agent.attacked", event)
    }

    @EventListener
    fun on(event: WorldEvent.AgentDied) = publish(event.agent, "agent.died", event)

    @EventListener
    fun on(event: WorldEvent.AgentRespawned) = publish(event.agent, "agent.respawned", event)

    @EventListener
    fun on(event: WorldEvent.SafeNodeSet) = publish(event.agent, "agent.safe_node_set", event)

    @EventListener
    fun on(event: WorldEvent.BuildingPlaced) = publish(event.building.builtByAgentId, "building.placed", event)

    @EventListener
    fun on(event: WorldEvent.BuildingProgressed) = publish(event.building.builtByAgentId, "building.progressed", event)

    @EventListener
    fun on(event: WorldEvent.BuildingCompleted) = publish(event.building.builtByAgentId, "building.completed", event)

    @EventListener
    fun on(event: WorldEvent.ItemDeposited) = publish(event.agent, "item.deposited", event)

    @EventListener
    fun on(event: WorldEvent.ItemWithdrawn) = publish(event.agent, "item.withdrawn", event)

    @EventListener
    fun on(event: WorldEvent.ItemPickedUp) = publish(event.agent, "item.pickedUp", event)

    @EventListener
    fun on(event: WorldEvent.ItemDroppedOnGround) = publish(event.byAgent, "item.droppedOnGround", event)

    @EventListener
    fun on(event: WorldEvent.AbilityUsed) {
        publish(event.agent, "ability.used", event)
        val target = event.target
        if (target != null && target != event.agent) publish(target, "ability.used", event)
    }

    @EventListener
    fun on(event: WorldEvent.PerkTriggered) {
        publish(event.agent, "perk.triggered", event)
        val target = event.target
        if (target != null && target != event.agent) publish(target, "perk.triggered", event)
    }

    @EventListener
    fun on(event: WorldEvent.DerivedPoolsRefreshed) = publish(event.agent, "pools.refreshed", event)

    private fun publish(agent: AgentId, type: String, payload: Any) {
        val tick = (payload as? WorldEvent)?.tick
            ?: (payload as? AgentEvent)?.tick
            ?: (payload as? PassivesPayload)?.tick
            ?: 0L
        val appended = log.append(agent, type, tick, mapper.valueToTree(payload))
        logger.info("dispatch agent={} type={} tick={} seq={}", agent.id, type, tick, appended.seq)
        try {
            bus.publish(InvalidationMessage.AgentNotify(agent))
        } catch (e: Exception) {
            // Pub/sub is best-effort: the event is durable in the log and will be read on
            // next subscribe + read; only the wakeup latency is affected.
            logger.debug("publish AgentNotify for {} failed: {}", agent.id, e.message)
        }
    }

    internal data class PassivesPayload(
        val agent: AgentId,
        val delta: BodyDelta,
        val tick: Long,
    )
}
