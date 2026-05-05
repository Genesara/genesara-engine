package dev.gvart.genesara.world.internal.combat

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.player.SkillProgression
import dev.gvart.genesara.world.DamageType
import dev.gvart.genesara.world.EquipSlot
import dev.gvart.genesara.world.EquipmentInstanceStore
import dev.gvart.genesara.world.Item
import dev.gvart.genesara.world.ItemLookup
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.WorldRejection
import dev.gvart.genesara.world.commands.WorldCommand
import dev.gvart.genesara.world.events.WorldEvent
import dev.gvart.genesara.world.internal.balance.BalanceLookup
import dev.gvart.genesara.world.internal.death.AttackCause
import dev.gvart.genesara.world.internal.death.DeathProcessor
import dev.gvart.genesara.world.internal.worldstate.WorldState
import kotlin.random.Random

/**
 * Reducer for [WorldCommand.AttackTarget]. Slice 1 combat shape:
 *   damage = attackerStat × weaponPower (armor=0, typeMod=1.0 today)
 *   dodge first; if dodged, hpLost=0. Else crit; if crit, hpLost = base × critMultiplier.
 *
 * RNG is injected so tests can pass a seeded [Random] to pin crit/dodge outcomes.
 * Production wires `Random.Default` via the same constructor-default pattern as
 * [dev.gvart.genesara.world.internal.crafting.RarityRoller].
 *
 * Killing-blow propagation: when the post-damage HP hits 0, the reducer calls
 * [DeathProcessor.applyDeath] inline with an [AttackCause] so [WorldEvent.AgentDied]
 * lands at the same tick with `causedBy = command.commandId` and the attacker's
 * kill streak ticks up via [WorldState.incrementKillStreak].
 *
 * TODO(combat-durability): weapons are not consumed on attack in Slice 1. Land
 * with the durability slice (separate combat issue).
 */
internal fun reduceAttack(
    state: WorldState,
    command: WorldCommand.AttackTarget,
    balance: BalanceLookup,
    items: ItemLookup,
    agents: AgentRegistry,
    equipment: EquipmentInstanceStore,
    progression: SkillProgression,
    deathProcessor: DeathProcessor,
    rng: Random,
    tick: Long,
): Either<WorldRejection, Pair<WorldState, List<WorldEvent>>> = either {
    ensure(command.agent != command.target) { WorldRejection.CannotAttackSelf(command.agent) }

    val attackerNode = ensureNotNull(state.positions[command.agent]) {
        WorldRejection.NotInWorld(command.agent)
    }
    val targetNode = ensureNotNull(state.positions[command.target]) {
        WorldRejection.TargetNotInWorld(command.agent, command.target)
    }

    val weaponDef = equipment.equippedFor(command.agent)[EquipSlot.MAIN_HAND]
        ?.let { items.byId(it.itemId) }
    val weaponProfile = weaponProfileFor(weaponDef, balance)

    ensure(isWithinRange(state, attackerNode, targetNode, weaponProfile.range)) {
        WorldRejection.TargetOutOfRange(
            command.agent, command.target, attackerNode, targetNode, weaponProfile.range,
        )
    }

    val targetBody = state.bodyOf(command.target)
        ?: error("Invariant violated: target ${command.target} positioned but has no body")
    ensure(targetBody.hp > 0) { WorldRejection.TargetAlreadyDead(command.agent, command.target) }

    val attackerBody = state.bodyOf(command.agent)
        ?: error("Invariant violated: attacker ${command.agent} positioned but has no body")
    val staminaCost = balance.attackStaminaCost()
    ensure(attackerBody.stamina >= staminaCost) {
        WorldRejection.NotEnoughStamina(command.agent, staminaCost, attackerBody.stamina)
    }

    val attacker = agents.find(command.agent)
        ?: error("Invariant violated: attacker ${command.agent} positioned but has no registry row")
    val defender = agents.find(command.target)
        ?: error("Invariant violated: target ${command.target} positioned but has no registry row")

    val attackerStat = balance.combatStatFor(weaponProfile.combatSkill).valueOn(attacker.attributes)
    val rawDamage = attackerStat * weaponProfile.weaponPower
    val typedDamage = (rawDamage * balance.damageTypeModifier(weaponProfile.damageType))
        .toInt()
        .coerceAtLeast(0)

    // Dodge rolls FIRST so a successful dodge short-circuits the crit roll. Otherwise a crit
    // followed by a dodge would burn the RNG cursor on a discarded crit and shift downstream
    // rolls — keeping order matters for reproducible seeds.
    val dodgeChance = balance.dodgeChancePercent(defender.attributes.dexterity)
    val isDodged = rng.nextInt(100) < dodgeChance
    val (hpLost, isCrit) = if (isDodged) {
        0 to false
    } else {
        val critChance = balance.critChancePercent(attacker.attributes.luck)
        val crit = rng.nextInt(100) < critChance
        val landed = if (crit) typedDamage * balance.critMultiplier() else typedDamage
        landed to crit
    }

    val nextTargetBody = targetBody.takeDamage(hpLost)
    val nextAttackerBody = attackerBody.spendStamina(staminaCost)
    var nextState = state
        .updateBody(command.target, nextTargetBody)
        .updateBody(command.agent, nextAttackerBody)

    progression.accrueXp(command.agent, weaponProfile.combatSkill, balance.attackXpDelta(), tick, command.commandId)

    val attackEvent = WorldEvent.AgentAttacked(
        attacker = command.agent,
        target = command.target,
        at = attackerNode,
        damageType = weaponProfile.damageType,
        baseDamage = typedDamage,
        hpLost = hpLost,
        isCrit = isCrit,
        isDodged = isDodged,
        targetHpAfter = nextTargetBody.hp,
        targetKilled = nextTargetBody.hp == 0,
        tick = tick,
        causedBy = command.commandId,
    )

    val emitted = mutableListOf<WorldEvent>(attackEvent)
    if (nextTargetBody.hp == 0) {
        val (afterDeath, deathEvents) = deathProcessor.applyDeath(
            state = nextState,
            agentId = command.target,
            deathNode = targetNode,
            cause = AttackCause(commandId = command.commandId, attackerId = command.agent),
            tick = tick,
            rng = rng,
        )
        nextState = afterDeath
        emitted += deathEvents
    }

    nextState to emitted.toList()
}

private data class WeaponProfile(
    val damageType: DamageType,
    val weaponPower: Int,
    val combatSkill: SkillId,
    val range: Int,
)

private fun weaponProfileFor(weapon: Item?, balance: BalanceLookup): WeaponProfile {
    val damageType = weapon?.damageType ?: balance.unarmedDamageType()
    val weaponPower = weapon?.weaponPower ?: balance.unarmedWeaponPower()
    val combatSkill = weapon?.combatSkill ?: balance.unarmedCombatSkill()
    val range = weapon?.range ?: balance.unarmedRange()
    return WeaponProfile(damageType, weaponPower, combatSkill, range)
}

/**
 * BFS over [WorldState.nodes] adjacency, capped at [range] hops, looking for
 * [target] from [from]. Range 1 = same-node only (no hops). Range 2 = same node
 * or any direct neighbor. Higher ranges follow the adjacency graph further.
 * Stops as soon as the target is reachable, so the worst case is one full
 * `range`-hop expansion of the starting node's neighborhood.
 */
private fun isWithinRange(state: WorldState, from: NodeId, target: NodeId, range: Int): Boolean {
    if (from == target) return true
    if (range <= 1) return false
    val visited = mutableSetOf(from)
    var frontier: Set<NodeId> = setOf(from)
    repeat(range - 1) {
        val next = mutableSetOf<NodeId>()
        for (nodeId in frontier) {
            val node = state.nodes[nodeId] ?: continue
            for (neighbor in node.adjacency) {
                if (neighbor == target) return true
                if (visited.add(neighbor)) next += neighbor
            }
        }
        if (next.isEmpty()) return false
        frontier = next
    }
    return false
}
