package dev.gvart.genesara.world.environment.internal.npc

import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.Npc
import dev.gvart.genesara.world.NpcCatalog
import dev.gvart.genesara.world.NpcId
import dev.gvart.genesara.world.NpcType
import dev.gvart.genesara.world.NpcsStore
import dev.gvart.genesara.world.environment.AdminNpcGateway
import dev.gvart.genesara.world.environment.AdminNpcGatewayError
import dev.gvart.genesara.world.environment.KillOutcome
import dev.gvart.genesara.world.events.EconomyEvent
import dev.gvart.genesara.world.events.EnvironmentEvent
import java.util.UUID
import kotlin.random.Random
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component

@Component
internal class AdminNpcGatewayImpl(
    private val store: NpcsStore,
    private val catalog: NpcCatalog,
    private val lootRoll: LootRoll,
    private val publisher: ApplicationEventPublisher,
) : AdminNpcGateway {

    override fun spawn(nodeId: NodeId, type: NpcType, hp: Int?, tick: Long): Npc {
        val def = catalog.byType(type) ?: throw AdminNpcGatewayError.UnknownType(type)
        val hpCurrent = hp ?: def.hpMax
        if (hpCurrent !in 1..def.hpMax) {
            throw AdminNpcGatewayError.InvalidHp(hpCurrent, def.hpMax)
        }
        val npc = Npc(
            id = NpcId(UUID.randomUUID()),
            type = type,
            nodeId = nodeId,
            spawnNodeId = nodeId,
            hpCurrent = hpCurrent,
            hpMax = def.hpMax,
            spawnedAtTick = tick,
            lastAttackTick = tick,
        )
        store.insert(npc)
        publisher.publishEvent(
            EnvironmentEvent.NpcSpawned(
                npc = npc.id,
                npcType = npc.type,
                at = npc.nodeId,
                hpMax = npc.hpMax,
                tick = tick,
                causedBy = null,
            )
        )
        return npc
    }

    override fun listAtNode(nodeId: NodeId): List<Npc> = store.byNodes(listOf(nodeId))

    override fun update(npcId: NpcId, hp: Int?, nodeId: NodeId?, tick: Long): Npc {
        val current = store.findById(npcId) ?: throw AdminNpcGatewayError.NotFound(npcId)
        val nextHp = hp ?: current.hpCurrent
        if (nextHp !in 1..current.hpMax) {
            throw AdminNpcGatewayError.InvalidHp(nextHp, current.hpMax)
        }
        val next = current.copy(
            hpCurrent = nextHp,
            nodeId = nodeId ?: current.nodeId,
        )
        store.update(next)
        return next
    }

    override fun kill(npcId: NpcId, silent: Boolean, tick: Long): KillOutcome {
        val current = store.findById(npcId) ?: throw AdminNpcGatewayError.NotFound(npcId)
        if (silent) {
            store.delete(npcId)
            return KillOutcome(npc = current, drops = emptyList())
        }
        val drops = lootRoll.rollAndDeposit(
            npcType = current.type,
            node = current.nodeId,
            killerCombatSkillLevel = 0,
            killerLuck = 0,
            huntingLootBonus = 0.0,
            tick = tick,
            rng = Random.Default,
        )
        store.delete(npcId)
        publisher.publishEvent(
            EnvironmentEvent.NpcDied(
                npc = current.id,
                npcType = current.type,
                at = current.nodeId,
                killedBy = null,
                drops = drops,
                tick = tick,
                causedBy = null,
            )
        )
        for (drop in drops) {
            publisher.publishEvent(
                EconomyEvent.ItemDroppedOnGround(
                    at = current.nodeId,
                    byAgent = null,
                    drop = drop,
                    tick = tick,
                    causedBy = null,
                )
            )
        }
        return KillOutcome(npc = current, drops = drops)
    }
}
