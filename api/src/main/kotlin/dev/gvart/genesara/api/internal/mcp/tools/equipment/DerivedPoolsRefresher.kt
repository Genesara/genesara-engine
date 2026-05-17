package dev.gvart.genesara.api.internal.mcp.tools.equipment

import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.player.AttributeDerivation
import dev.gvart.genesara.world.EffectiveAttributes
import dev.gvart.genesara.world.EquipmentBonusAggregator
import dev.gvart.genesara.world.WorldCommandGateway
import dev.gvart.genesara.world.commands.BodyCommand
import org.springframework.stereotype.Component

/**
 * Single entry point for "recompute max pools after something that changes
 * the inputs". Both equip/unequip AND allocate_points must go through here
 * — otherwise an `allocate_points` call would wipe the equipment-derived
 * component of maxHp/Stamina/Mana until the next equip refresh.
 *
 * Computes effective attributes (base + equipped attribute bonuses) and
 * queues a [BodyCommand.RefreshDerivedPools] at tick+1 so the body
 * record's max cache reflects the new state next tick.
 *
 * **Current value semantics.** When the new max is **higher** (equipped
 * +CON gear, spent attribute points), current HP/Stamina/Mana are
 * preserved — a player at 50/100 stays at 50/120. When the new max is
 * **lower** (unequipped +CON gear, de-leveled), the
 * [dev.gvart.genesara.world.body.internal.body.reduceRefreshDerivedPools]
 * reducer clamps current values down to the new max via `coerceAtMost`.
 * D2 cap semantics, not WoW proportional rescaling.
 */
internal fun interface DerivedPoolsRefresher {
    fun refresh(agent: AgentId)

    companion object {
        /** No-op refresher for tests that don't exercise pool recompute. */
        val NoOp: DerivedPoolsRefresher = DerivedPoolsRefresher { /* nothing */ }
    }
}

@Component
internal class DerivedPoolsRefresherImpl(
    private val agents: AgentRegistry,
    private val bonuses: EquipmentBonusAggregator,
    private val world: WorldCommandGateway,
    private val tickClock: TickClock,
) : DerivedPoolsRefresher {

    override fun refresh(agent: AgentId) {
        val record = agents.find(agent)
            ?: error("derived-pool refresh: agent $agent missing from registry — upstream invariant broken")
        val effective = EffectiveAttributes.compute(record.attributes, agent, bonuses)
        val pools = AttributeDerivation.deriveMaxPools(effective)
        world.submit(
            BodyCommand.RefreshDerivedPools(
                agent = agent,
                maxHp = pools.maxHp,
                maxStamina = pools.maxStamina,
                maxMana = pools.maxMana,
            ),
            appliesAtTick = tickClock.currentTick() + 1,
        )
    }
}
