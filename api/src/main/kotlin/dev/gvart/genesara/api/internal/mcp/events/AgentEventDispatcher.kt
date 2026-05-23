package dev.gvart.genesara.api.internal.mcp.events

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.events.AgentEvent
import dev.gvart.genesara.world.BodyDelta
import dev.gvart.genesara.world.events.BodyEvent
import dev.gvart.genesara.world.events.CombatEvent
import dev.gvart.genesara.world.events.CoreEvent
import dev.gvart.genesara.world.events.EconomyEvent
import dev.gvart.genesara.world.events.EnvironmentEvent
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
    fun on(event: CoreEvent.AgentMoved) = publish(event.agent, "agent.moved", event)

    @EventListener
    fun on(event: CoreEvent.AgentSpawned) = publish(event.agent, "agent.spawned", event)

    @EventListener
    fun on(event: CoreEvent.AgentDespawned) = publish(event.agent, "agent.despawned", event)

    @EventListener
    fun on(event: EconomyEvent.ResourceHarvested) = publish(event.agent, "resource.harvested", event)

    @EventListener
    fun on(event: EconomyEvent.CropPlanted) = publish(event.agent, "crop.planted", event)

    @EventListener
    fun on(event: EconomyEvent.CropTended) = publish(event.agent, "crop.tended", event)

    @EventListener
    fun on(event: EconomyEvent.CropHarvested) = publish(event.agent, "crop.harvested", event)

    @EventListener
    fun on(event: EconomyEvent.CropDied) = publish(event.agent, "crop.died", event)

    @EventListener
    fun on(event: BodyEvent.ItemConsumed) = publish(event.agent, "item.consumed", event)

    @EventListener
    fun on(event: BodyEvent.AgentDrank) = publish(event.agent, "agent.drank", event)

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
    fun on(event: AgentEvent.RecipeLearned) = publish(event.agent, "recipe.learned", event)

    @EventListener
    fun on(event: AgentEvent.CharacterXpGained) = publish(event.agent, "agent.xp_gained", event)

    @EventListener
    fun on(event: AgentEvent.AgentLeveled) = publish(event.agent, "agent.leveled", event)

    @EventListener
    fun on(event: EconomyEvent.ItemCrafted) = publish(event.agent, "item.crafted", event)

    @EventListener
    fun on(event: CoreEvent.CommandRejected) = publish(event.agent, "command.rejected", event)

    @EventListener
    fun on(event: BodyEvent.PassivesApplied) {
        event.deltas.forEach { (agent, delta) ->
            publish(agent, "agent.passives", PassivesPayload(agent, delta, event.tick))
        }
    }

    @EventListener
    fun on(event: CombatEvent.AgentAttacked) {
        publish(event.attacker, "agent.attacked", event)
        if (event.attacker != event.target) publish(event.target, "agent.attacked", event)
    }

    @EventListener
    fun on(event: BodyEvent.AgentDied) = publish(event.agent, "agent.died", event)

    @EventListener
    fun on(event: BodyEvent.AgentRespawned) = publish(event.agent, "agent.respawned", event)

    @EventListener
    fun on(event: CoreEvent.SafeNodeSet) = publish(event.agent, "agent.safe_node_set", event)

    @EventListener
    fun on(event: EnvironmentEvent.BuildingProgressed) = publish(event.agent, "building.progressed", event)

    @EventListener
    fun on(event: EnvironmentEvent.BuildingConstructed) = publish(event.agent, "building.constructed", event)

    @EventListener
    fun on(event: EnvironmentEvent.ItemDeposited) = publish(event.agent, "item.deposited", event)

    @EventListener
    fun on(event: EnvironmentEvent.ItemWithdrawn) = publish(event.agent, "item.withdrawn", event)

    @EventListener
    fun on(event: BodyEvent.ItemPickedUp) = publish(event.agent, "item.pickedUp", event)

    @EventListener
    fun on(event: EconomyEvent.ItemDroppedOnGround) {
        val byAgent = event.byAgent ?: return
        publish(byAgent, "item.droppedOnGround", event)
    }

    @EventListener
    fun on(event: CombatEvent.AbilityUsed) {
        publish(event.agent, "ability.used", event)
        val target = event.target
        if (target != null && target != event.agent) publish(target, "ability.used", event)
    }

    @EventListener
    fun on(event: CombatEvent.PerkTriggered) {
        publish(event.agent, "perk.triggered", event)
        val target = event.target
        if (target != null && target != event.agent) publish(target, "perk.triggered", event)
    }

    @EventListener
    fun on(event: BodyEvent.DerivedPoolsRefreshed) = publish(event.agent, "pools.refreshed", event)

    @EventListener
    fun on(event: CoreEvent.AgentSpoke) {
        event.listeners.forEach { listener -> publish(listener, "agent.spoke", event) }
    }

    @EventListener
    fun on(event: EconomyEvent.TradeOfferReceived) {
        event.listeners.forEach { listener -> publish(listener, "trade.offer_received", event) }
    }

    @EventListener
    fun on(event: EconomyEvent.TradeAccepted) {
        event.listeners.forEach { listener -> publish(listener, "trade.accepted", event) }
    }

    @EventListener
    fun on(event: EconomyEvent.TradeRejected) {
        event.listeners.forEach { listener -> publish(listener, "trade.rejected", event) }
    }

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
