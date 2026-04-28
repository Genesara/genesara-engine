package dev.gvart.genesara.world.internal.passive

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.BodyDelta
import dev.gvart.genesara.world.Gauge
import dev.gvart.genesara.world.events.WorldEvent
import dev.gvart.genesara.world.internal.balance.BalanceLookup
import dev.gvart.genesara.world.internal.worldstate.WorldState

internal fun interface Passive {
    fun deltasFor(state: WorldState, balance: BalanceLookup): Map<AgentId, BodyDelta>
}

/**
 * Stamina regen gated on survival vitals: an agent whose hunger / thirst / sleep is at
 * or below its per-gauge low threshold doesn't recover. HP / Mana regen will read the
 * same per-gauge gate when those passives ship.
 */
internal val staminaRegenPassive = Passive { state, balance ->
    state.bodies.mapNotNull { (id, body) ->
        if (body.isVitalsLow(balance::gaugeLowThreshold)) return@mapNotNull null
        val nodeId = state.positions[id] ?: return@mapNotNull null
        val node = state.nodes[nodeId] ?: return@mapNotNull null
        val region = state.regions[node.regionId] ?: return@mapNotNull null
        val climate = region.climate ?: return@mapNotNull null
        val regen = balance.staminaRegenPerTick(climate)
        if (regen == 0) null else id to BodyDelta(stamina = regen)
    }.toMap()
}

/**
 * Drains every survival gauge by a flat amount per tick. Applies to every body, not
 * just spawned-in agents — even logged-out agents grow hungry over real time. (Sleep
 * regen on offline-time deltas is a separate future passive.)
 *
 * Fast-path returns an empty map when all gauges have zero drain — saves one allocation
 * per body per tick in tests and any future "rested" world configuration.
 */
internal val gaugeDrainPassive = Passive { state, balance ->
    val hungerDrain = -balance.gaugeDrainPerTick(Gauge.HUNGER)
    val thirstDrain = -balance.gaugeDrainPerTick(Gauge.THIRST)
    val sleepDrain = -balance.gaugeDrainPerTick(Gauge.SLEEP)
    if (hungerDrain == 0 && thirstDrain == 0 && sleepDrain == 0) {
        emptyMap()
    } else {
        state.bodies.mapValues { _ ->
            BodyDelta(hunger = hungerDrain, thirst = thirstDrain, sleep = sleepDrain)
        }
    }
}

/**
 * Starvation damage: when any survival gauge has hit zero, the body loses HP per tick.
 * A single tick of damage covers all zero-gauges combined — agents die from prolonged
 * neglect, not from triple-overlap punishment.
 */
internal val starvationDamagePassive = Passive { state, balance ->
    val damage = balance.starvationDamagePerTick()
    state.bodies.mapNotNull { (id, body) ->
        if (!body.isStarving()) null else id to BodyDelta(hp = -damage)
    }.toMap()
}

internal fun applyPassives(
    state: WorldState,
    balance: BalanceLookup,
    tick: Long,
    passives: List<Passive> = listOf(gaugeDrainPassive, staminaRegenPassive, starvationDamagePassive),
): Pair<WorldState, WorldEvent.PassivesApplied?> {
    val desired: Map<AgentId, BodyDelta> = passives
        .flatMap { it.deltasFor(state, balance).entries }
        .groupBy({ it.key }, { it.value })
        .mapValues { (_, deltas) -> deltas.fold(BodyDelta()) { acc, d -> acc + d } }

    if (desired.isEmpty()) return state to null

    val applied = mutableMapOf<AgentId, BodyDelta>()
    val nextBodies = state.bodies.toMutableMap()
    for ((id, want) in desired) {
        val body = nextBodies[id] ?: continue
        val newHp = (body.hp + want.hp).coerceIn(0, body.maxHp)
        val newSt = (body.stamina + want.stamina).coerceIn(0, body.maxStamina)
        val newMn = (body.mana + want.mana).coerceIn(0, body.maxMana)
        val newHg = (body.hunger + want.hunger).coerceIn(0, body.maxHunger)
        val newTh = (body.thirst + want.thirst).coerceIn(0, body.maxThirst)
        val newSl = (body.sleep + want.sleep).coerceIn(0, body.maxSleep)
        val realDelta = BodyDelta(
            hp = newHp - body.hp,
            stamina = newSt - body.stamina,
            mana = newMn - body.mana,
            hunger = newHg - body.hunger,
            thirst = newTh - body.thirst,
            sleep = newSl - body.sleep,
        )
        if (realDelta.isZero) continue
        nextBodies[id] = body.copy(
            hp = newHp,
            stamina = newSt,
            mana = newMn,
            hunger = newHg,
            thirst = newTh,
            sleep = newSl,
        )
        applied[id] = realDelta
    }

    if (applied.isEmpty()) return state to null

    val nextState = state.copy(bodies = nextBodies)
    val event = WorldEvent.PassivesApplied(applied, tick)
    return nextState to event
}
