package dev.gvart.genesara.world.body.internal.admin

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentProfileLookup
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.world.AgentBodyAdminGateway
import dev.gvart.genesara.world.AgentBodyAdminResult
import dev.gvart.genesara.world.AgentSafeNodeGateway
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.WorldQueryGateway
import dev.gvart.genesara.world.events.BodyEvent
import dev.gvart.genesara.world.events.CoreEvent
import dev.gvart.genesara.world.internal.jooq.tables.references.AGENT_BODIES
import dev.gvart.genesara.world.internal.jooq.tables.references.AGENT_POSITIONS
import dev.gvart.genesara.world.internal.jooq.tables.references.NODES
import dev.gvart.genesara.world.internal.jooq.tables.references.REGIONS
import org.jooq.DSLContext
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Component
internal class JooqAgentBodyAdminGateway(
    private val dsl: DSLContext,
    private val publisher: ApplicationEventPublisher,
    private val safeNodes: AgentSafeNodeGateway,
    private val profiles: AgentProfileLookup,
    private val agents: AgentRegistry,
    private val world: WorldQueryGateway,
) : AgentBodyAdminGateway {

    @Transactional
    override fun setGauges(
        agent: AgentId,
        hp: Int?,
        stamina: Int?,
        mana: Int?,
        hunger: Int?,
        thirst: Int?,
        sleep: Int?,
    ): AgentBodyAdminResult {
        val row = dsl.select(
            AGENT_BODIES.HP, AGENT_BODIES.MAX_HP,
            AGENT_BODIES.STAMINA, AGENT_BODIES.MAX_STAMINA,
            AGENT_BODIES.MANA, AGENT_BODIES.MAX_MANA,
            AGENT_BODIES.HUNGER, AGENT_BODIES.MAX_HUNGER,
            AGENT_BODIES.THIRST, AGENT_BODIES.MAX_THIRST,
            AGENT_BODIES.SLEEP, AGENT_BODIES.MAX_SLEEP,
        )
            .from(AGENT_BODIES)
            .where(AGENT_BODIES.AGENT_ID.eq(agent.id))
            .fetchOne() ?: return AgentBodyAdminResult.AgentNotFound(agent)

        val applied = mutableMapOf<String, Int>()
        val update = dsl.update(AGENT_BODIES).set(AGENT_BODIES.AGENT_ID, agent.id)

        hp?.let { value ->
            val clamped = value.coerceIn(0, row[AGENT_BODIES.MAX_HP]!!)
            update.set(AGENT_BODIES.HP, clamped)
            applied["hp"] = clamped
        }
        stamina?.let { value ->
            val clamped = value.coerceIn(0, row[AGENT_BODIES.MAX_STAMINA]!!)
            update.set(AGENT_BODIES.STAMINA, clamped)
            applied["stamina"] = clamped
        }
        mana?.let { value ->
            val clamped = value.coerceIn(0, row[AGENT_BODIES.MAX_MANA]!!)
            update.set(AGENT_BODIES.MANA, clamped)
            applied["mana"] = clamped
        }
        hunger?.let { value ->
            val clamped = value.coerceIn(0, row[AGENT_BODIES.MAX_HUNGER]!!)
            update.set(AGENT_BODIES.HUNGER, clamped)
            applied["hunger"] = clamped
        }
        thirst?.let { value ->
            val clamped = value.coerceIn(0, row[AGENT_BODIES.MAX_THIRST]!!)
            update.set(AGENT_BODIES.THIRST, clamped)
            applied["thirst"] = clamped
        }
        sleep?.let { value ->
            val clamped = value.coerceIn(0, row[AGENT_BODIES.MAX_SLEEP]!!)
            update.set(AGENT_BODIES.SLEEP, clamped)
            applied["sleep"] = clamped
        }

        if (applied.isNotEmpty()) {
            update.where(AGENT_BODIES.AGENT_ID.eq(agent.id)).execute()
        }
        return AgentBodyAdminResult.GaugesUpdated(applied)
    }

    @Transactional
    override fun teleport(agent: AgentId, nodeId: NodeId, tick: Long): AgentBodyAdminResult {
        val targetWorldId = dsl.select(REGIONS.WORLD_ID)
            .from(NODES).join(REGIONS).on(REGIONS.ID.eq(NODES.REGION_ID))
            .where(NODES.ID.eq(nodeId.value))
            .fetchOne(REGIONS.WORLD_ID) ?: return AgentBodyAdminResult.NodeNotFound(nodeId)

        val priorRow = dsl.select(AGENT_POSITIONS.NODE_ID, AGENT_POSITIONS.WORLD_ID, AGENT_POSITIONS.ACTIVE)
            .from(AGENT_POSITIONS)
            .where(AGENT_POSITIONS.AGENT_ID.eq(agent.id))
            .fetchOne()

        if (agents.find(agent) == null && priorRow == null) {
            return AgentBodyAdminResult.AgentNotFound(agent)
        }

        val priorNode = priorRow?.get(AGENT_POSITIONS.NODE_ID)?.let(::NodeId)
        val priorWorld = priorRow?.get(AGENT_POSITIONS.WORLD_ID)
        val priorActive = priorRow?.get(AGENT_POSITIONS.ACTIVE) ?: false
        val crossedWorld = priorWorld != null && priorWorld != targetWorldId

        dsl.insertInto(AGENT_POSITIONS)
            .set(AGENT_POSITIONS.AGENT_ID, agent.id)
            .set(AGENT_POSITIONS.NODE_ID, nodeId.value)
            .set(AGENT_POSITIONS.WORLD_ID, targetWorldId)
            .set(AGENT_POSITIONS.ACTIVE, priorActive)
            .onConflict(AGENT_POSITIONS.AGENT_ID)
            .doUpdate()
            .set(AGENT_POSITIONS.NODE_ID, nodeId.value)
            .set(AGENT_POSITIONS.WORLD_ID, targetWorldId)
            .execute()

        publisher.publishEvent(
            CoreEvent.AgentMoved(
                agent = agent,
                from = priorNode ?: nodeId,
                to = nodeId,
                staminaSpent = 0,
                tick = tick,
                causedBy = UUID.randomUUID(),
            ),
        )

        return AgentBodyAdminResult.Teleported(from = priorNode, to = nodeId, crossedWorld = crossedWorld)
    }

    @Transactional
    override fun forceRespawn(agent: AgentId, tick: Long): AgentBodyAdminResult {
        val profile = profiles.find(agent) ?: return AgentBodyAdminResult.AgentNotFound(agent)
        val landing = resolveLanding(agent) ?: return AgentBodyAdminResult.NoSpawnableNode(agent)

        val targetWorldId = dsl.select(REGIONS.WORLD_ID)
            .from(NODES).join(REGIONS).on(REGIONS.ID.eq(NODES.REGION_ID))
            .where(NODES.ID.eq(landing.nodeId.value))
            .fetchOne(REGIONS.WORLD_ID) ?: return AgentBodyAdminResult.NoSpawnableNode(agent)

        dsl.insertInto(AGENT_BODIES)
            .set(AGENT_BODIES.AGENT_ID, agent.id)
            .set(AGENT_BODIES.HP, profile.maxHp)
            .set(AGENT_BODIES.MAX_HP, profile.maxHp)
            .set(AGENT_BODIES.STAMINA, profile.maxStamina)
            .set(AGENT_BODIES.MAX_STAMINA, profile.maxStamina)
            .set(AGENT_BODIES.MANA, profile.maxMana)
            .set(AGENT_BODIES.MAX_MANA, profile.maxMana)
            .set(AGENT_BODIES.HUNGER, FULL_GAUGE)
            .set(AGENT_BODIES.MAX_HUNGER, FULL_GAUGE)
            .set(AGENT_BODIES.THIRST, FULL_GAUGE)
            .set(AGENT_BODIES.MAX_THIRST, FULL_GAUGE)
            .set(AGENT_BODIES.SLEEP, FULL_GAUGE)
            .set(AGENT_BODIES.MAX_SLEEP, FULL_GAUGE)
            .onConflict(AGENT_BODIES.AGENT_ID)
            .doUpdate()
            .set(AGENT_BODIES.HP, profile.maxHp)
            .set(AGENT_BODIES.STAMINA, profile.maxStamina)
            .set(AGENT_BODIES.MANA, profile.maxMana)
            .set(AGENT_BODIES.HUNGER, FULL_GAUGE)
            .set(AGENT_BODIES.THIRST, FULL_GAUGE)
            .set(AGENT_BODIES.SLEEP, FULL_GAUGE)
            .execute()

        dsl.insertInto(AGENT_POSITIONS)
            .set(AGENT_POSITIONS.AGENT_ID, agent.id)
            .set(AGENT_POSITIONS.NODE_ID, landing.nodeId.value)
            .set(AGENT_POSITIONS.WORLD_ID, targetWorldId)
            .set(AGENT_POSITIONS.ACTIVE, true)
            .onConflict(AGENT_POSITIONS.AGENT_ID)
            .doUpdate()
            .set(AGENT_POSITIONS.NODE_ID, landing.nodeId.value)
            .set(AGENT_POSITIONS.WORLD_ID, targetWorldId)
            .set(AGENT_POSITIONS.ACTIVE, true)
            .execute()

        publisher.publishEvent(
            BodyEvent.AgentRespawned(
                agent = agent,
                at = landing.nodeId,
                fromCheckpoint = landing.fromCheckpoint,
                tick = tick,
                causedBy = UUID.randomUUID(),
            ),
        )

        return AgentBodyAdminResult.Respawned(at = landing.nodeId, fromCheckpoint = landing.fromCheckpoint)
    }

    private fun resolveLanding(agent: AgentId): Landing? {
        safeNodes.find(agent)?.let { return Landing(it, fromCheckpoint = true) }
        val record = agents.find(agent)
        record?.let { world.starterNodeFor(it.race)?.let { node -> return Landing(node, fromCheckpoint = false) } }
        return world.randomSpawnableNode()?.let { Landing(it, fromCheckpoint = false) }
    }

    private data class Landing(val nodeId: NodeId, val fromCheckpoint: Boolean)

    private companion object {
        const val FULL_GAUGE = 100
    }
}
