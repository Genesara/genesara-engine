package dev.gvart.genesara.world.internal.passive

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.BodyDelta
import dev.gvart.genesara.world.Gauge
import dev.gvart.genesara.world.events.WorldEvent
import dev.gvart.genesara.world.internal.balance.BalanceLookup
import dev.gvart.genesara.world.internal.worldstate.WorldState
import kotlin.math.roundToInt

internal fun interface Passive {
    fun deltasFor(state: WorldState, balance: BalanceLookup): Map<AgentId, BodyDelta>
}

/**
 * Stamina regen gated on survival vitals: any gauge at-or-below its low threshold
 * suppresses regen entirely; both hunger and thirst at-or-above the buff threshold
 * multiply it. The low-gate runs first so halt and buff stay mutually exclusive. HP /
 * Mana regen will read the same gates when those passives ship.
 */
internal val staminaRegenPassive = Passive { state, balance ->
    state.bodies.mapNotNull { (id, body) ->
        if (body.isVitalsLow(balance::gaugeLowThreshold)) return@mapNotNull null
        val nodeId = state.positions[id] ?: return@mapNotNull null
        val node = state.nodes[nodeId] ?: return@mapNotNull null
        val region = state.regions[node.regionId] ?: return@mapNotNull null
        val climate = region.climate ?: return@mapNotNull null
        val baseRegen = balance.staminaRegenPerTick(climate)
        val regen = if (body.isVitalsHigh(balance::gaugeBuffThreshold)) {
            (baseRegen * balance.staminaRegenBuffMultiplier()).roundToInt()
        } else {
            baseRegen
        }
        if (regen == 0) null else id to BodyDelta(stamina = regen)
    }.toMap()
}

/**
 * Drains hunger and thirst by a flat amount per tick. Applies to every body, not just
 * spawned-in agents — even logged-out agents grow hungry over real time. Sleep is
 * handled separately by [sleepPassive] because its sign flips with online state.
 *
 * Fast-path returns an empty map when both drains are zero — saves one allocation per
 * body per tick in tests and any future "rested" world configuration.
 */
internal val gaugeDrainPassive = Passive { state, balance ->
    val hungerDrain = -balance.gaugeDrainPerTick(Gauge.HUNGER)
    val thirstDrain = -balance.gaugeDrainPerTick(Gauge.THIRST)
    if (hungerDrain == 0 && thirstDrain == 0) {
        emptyMap()
    } else {
        state.bodies.mapValues { _ ->
            BodyDelta(hunger = hungerDrain, thirst = thirstDrain)
        }
    }
}

/**
 * Sleep gauge per tick: drains while the agent is online (logged in), regens while the
 * agent is offline. The asymmetry is the canonical design — characters stay awake when
 * their operator is connected and rest in the down-time between sessions.
 *
 * Online status comes from [WorldState.isOnline] so any future change to how presence
 * is represented stays in one place.
 *
 * Other-gauge starvation does **not** halt sleep regen. The general "low → halts regen"
 * rule applies to HP / Stamina / Mana (driven by `isVitalsLow`); sleep recovery is its
 * own mechanic and an agent who logs out tired and hungry should still wake rested
 * (and dead, if no one fed them).
 *
 * Fast-path returns an empty map when both drain and regen are zero (e.g. in tests that
 * disable survival entirely).
 */
internal val sleepPassive = Passive { state, balance ->
    val drain = balance.gaugeDrainPerTick(Gauge.SLEEP)
    val regen = balance.sleepRegenPerOfflineTick()
    if (drain == 0 && regen == 0) {
        emptyMap()
    } else {
        state.bodies.mapNotNull { (id, _) ->
            val delta = if (state.isOnline(id)) -drain else regen
            if (delta == 0) null else id to BodyDelta(sleep = delta)
        }.toMap()
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
    passives: List<Passive> = listOf(gaugeDrainPassive, sleepPassive, staminaRegenPassive, starvationDamagePassive),
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
