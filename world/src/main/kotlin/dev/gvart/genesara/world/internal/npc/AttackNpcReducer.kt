package dev.gvart.genesara.world.internal.npc

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.player.ClassLookup
import dev.gvart.genesara.player.LevelScalingAggregator
import dev.gvart.genesara.player.NoOpClassLookup
import dev.gvart.genesara.player.PassiveAuraAggregator
import dev.gvart.genesara.player.ScalingEffect
import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.player.SkillProgression
import dev.gvart.genesara.world.AgentItemInstancesStore
import dev.gvart.genesara.world.AggressionProfile
import dev.gvart.genesara.world.DamageType
import dev.gvart.genesara.world.EquipSlot
import dev.gvart.genesara.world.EquipmentBonusAggregator
import dev.gvart.genesara.world.Item
import dev.gvart.genesara.world.ItemInstance
import dev.gvart.genesara.world.ItemLookup
import dev.gvart.genesara.world.NpcCatalog
import dev.gvart.genesara.world.NpcDef
import dev.gvart.genesara.world.WorldRejection
import dev.gvart.genesara.world.commands.WorldCommand
import dev.gvart.genesara.world.events.WorldEvent
import dev.gvart.genesara.world.internal.abilities.PendingAttackScaleStore
import dev.gvart.genesara.world.internal.balance.BalanceLookup
import dev.gvart.genesara.world.internal.behavior.ActionCategory
import dev.gvart.genesara.world.internal.behavior.BehaviorTracker
import dev.gvart.genesara.world.internal.worldstate.WorldState
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Reducer for [WorldCommand.AttackNpc] — the agent-vs-NPC parallel of
 * [dev.gvart.genesara.world.internal.combat.reduceAttack]. Reads agent-side
 * scaling/aura/class/equipment exactly like the agent-vs-agent path, but
 * resolves defender mitigation against the catalog's flat
 * [NpcDef.defense] and dodge against [NpcDef.dodgeChancePercent] instead
 * of attribute-driven values.
 *
 * On the killing blow:
 *  - rolls loot via [LootRoll] and deposits drops to the ground,
 *  - emits [WorldEvent.NpcDied] with the drops list + one
 *    [WorldEvent.ItemDroppedOnGround] per drop (mirror of the agent-death
 *    pattern in `DeathProcessor.applyDeath`),
 *  - awards the kill XP bonus on top of the per-swing XP,
 *  - removes the NPC from world state via [WorldState.removeNpc] which
 *    advances `nodesClearedThisTick` when the node empties.
 */
internal fun reduceAttackNpc(
    state: WorldState,
    command: WorldCommand.AttackNpc,
    balance: BalanceLookup,
    items: ItemLookup,
    agents: AgentRegistry,
    equipment: AgentItemInstancesStore,
    progression: SkillProgression,
    scaling: LevelScalingAggregator,
    passiveAura: PassiveAuraAggregator,
    equipmentBonuses: EquipmentBonusAggregator,
    pendingScales: PendingAttackScaleStore,
    behaviorTracker: BehaviorTracker,
    catalog: NpcCatalog,
    lootRoll: LootRoll,
    classes: ClassLookup = NoOpClassLookup,
    rng: Random,
    tick: Long,
): Either<WorldRejection, Pair<WorldState, List<WorldEvent>>> = either {
    val attackerNode = ensureNotNull(state.positions[command.agent]) {
        WorldRejection.NotInWorld(command.agent)
    }
    val npc = ensureNotNull(state.npcs[command.npc]) {
        WorldRejection.UnknownNpc(command.agent, command.npc)
    }
    val def = ensureNotNull(catalog.byType(npc.type)) {
        WorldRejection.UnknownNpc(command.agent, command.npc)
    }
    ensure(npc.hpCurrent > 0) {
        WorldRejection.NpcAlreadyDead(command.agent, command.npc)
    }

    val weaponInstance = equipment.equippedFor(command.agent)[EquipSlot.MAIN_HAND]
    val weaponDef = weaponInstance?.let { items.byId(it.itemId) }
    val weaponProfile = weaponProfileFor(weaponDef, weaponInstance, balance)

    val hops = hopDistance(state, attackerNode, npc.nodeId, weaponProfile.range)
    ensure(hops in 0..weaponProfile.range) {
        WorldRejection.NpcOutOfRange(
            command.agent, command.npc, attackerNode, npc.nodeId, weaponProfile.range,
        )
    }

    val attackerBody = state.bodyOf(command.agent)
        ?: error("Invariant violated: attacker ${command.agent} positioned but has no body")
    val staminaCost = balance.attackStaminaCost()
    ensure(attackerBody.stamina >= staminaCost) {
        WorldRejection.NotEnoughStamina(command.agent, staminaCost, attackerBody.stamina)
    }

    val attacker = agents.find(command.agent)
        ?: error("Invariant violated: attacker ${command.agent} positioned but has no registry row")

    val attackerStat = balance.combatStatFor(weaponProfile.combatSkill).valueOn(attacker.attributes)
    val rawDamage = attackerStat * weaponProfile.weaponPower
    val mitigatedRaw = (rawDamage - def.defense).coerceAtLeast(0)
    val typedDamage = (mitigatedRaw * balance.damageTypeModifier(weaponProfile.damageType))
        .toInt()
        .coerceAtLeast(0)
    val scalingEffect = scalingEffectFor(weaponProfile.damageType)
    val damageScaling = scalingEffect?.let { scaling.bonusFor(command.agent, it) } ?: 0.0
    val auraBonus = scalingEffect?.let { passiveAura.bonusFor(command.agent, it) } ?: 0
    val classMod = classes.damageMultiplier(attacker.classId, weaponProfile.damageType.name)
    val preClassScaled = ((typedDamage * (1.0 + damageScaling)).toInt() + auraBonus).coerceAtLeast(0)
    val baseScaled = (preClassScaled * classMod).toInt().coerceAtLeast(0)
    val pendingScalePct = pendingScales.consume(command.agent)
    val scaledDamage = if (pendingScalePct != null) {
        (baseScaled.toLong() * pendingScalePct / 100).toInt().coerceAtLeast(0)
    } else {
        baseScaled
    }

    val isDodged = def.dodgeChancePercent > 0 && rng.nextInt(100) < def.dodgeChancePercent
    val (hpLost, isCrit) = if (isDodged) {
        0 to false
    } else {
        val critChance = balance.critChancePercent(attacker.attributes.luck) +
            equipmentBonuses.passiveBuff(command.agent, ScalingEffect.CRIT_CHANCE)
        val crit = rng.nextInt(100) < critChance
        val landed = if (crit) scaledDamage * balance.critMultiplier() else scaledDamage
        landed to crit
    }

    val nextNpc = npc.takeDamage(hpLost)
    val nextAttackerBody = attackerBody.spendStamina(staminaCost)
    val killed = nextNpc.isDead

    var nextState = state
        .updateBody(command.agent, nextAttackerBody)
    nextState = if (killed) {
        nextState.removeNpc(npc.id, tick)
    } else {
        nextState.updateNpc(nextNpc)
    }

    progression.accrueXp(
        command.agent, weaponProfile.combatSkill, balance.attackXpDelta(),
        tick, command.commandId, attacker.classId,
    )
    if (killed) {
        progression.accrueXp(
            command.agent, weaponProfile.combatSkill, balance.npcKillXpBonus(),
            tick, command.commandId, attacker.classId,
        )
    }
    behaviorTracker.record(command.agent, ActionCategory.COMBAT, tick)

    val events = mutableListOf<WorldEvent>()
    events += WorldEvent.AgentAttackedNpc(
        attacker = command.agent,
        npc = npc.id,
        npcType = npc.type,
        at = npc.nodeId,
        damageType = weaponProfile.damageType,
        baseDamage = scaledDamage,
        hpLost = hpLost,
        isCrit = isCrit,
        isDodged = isDodged,
        npcHpAfter = nextNpc.hpCurrent,
        npcKilled = killed,
        tick = tick,
        causedBy = command.commandId,
    )

    if (killed) {
        // Skill level for the rarity roll is read from the registry's level
        // for the weapon's combat skill — `attacker.attributes` doesn't carry
        // skill level, so we approximate via the killer's overall level.
        val killerLevel = attacker.level
        val drops = lootRoll.rollAndDeposit(
            npcType = npc.type,
            node = npc.nodeId,
            killerCombatSkillLevel = killerLevel,
            killerLuck = attacker.attributes.luck,
            tick = tick,
            rng = rng,
        )
        events += WorldEvent.NpcDied(
            npc = npc.id,
            npcType = npc.type,
            at = npc.nodeId,
            killedBy = command.agent,
            drops = drops,
            tick = tick,
            causedBy = command.commandId,
        )
        for (drop in drops) {
            events += WorldEvent.ItemDroppedOnGround(
                at = npc.nodeId,
                byAgent = command.agent,
                drop = drop,
                tick = tick,
                causedBy = command.commandId,
            )
        }
    } else if (def.aggressionProfile == AggressionProfile.PASSIVE) {
        // PASSIVE flee inline (Q15b α): pick a random adjacent neighbor that
        // isn't the attacker's node. If none, the NPC stands and dies later.
        val node = state.nodes[npc.nodeId]
        val neighbors = node?.adjacency.orEmpty() - attackerNode
        if (neighbors.isNotEmpty()) {
            val destination = neighbors.elementAt(rng.nextInt(neighbors.size))
            val moved = nextNpc.moveTo(destination)
            nextState = nextState.updateNpc(moved)
            events += WorldEvent.NpcMoved(
                npc = npc.id,
                npcType = npc.type,
                from = npc.nodeId,
                to = destination,
                tick = tick,
            )
        }
    }

    nextState to events.toList()
}

private data class WeaponProfile(
    val damageType: DamageType,
    val weaponPower: Int,
    val combatSkill: SkillId,
    val range: Int,
)

private fun scalingEffectFor(type: DamageType): ScalingEffect? = when (type) {
    DamageType.SLASH -> ScalingEffect.SLASH_DAMAGE_BONUS
    DamageType.PIERCE -> ScalingEffect.PIERCE_DAMAGE_BONUS
    DamageType.BLUNT -> ScalingEffect.BLUNT_DAMAGE_BONUS
    DamageType.ENERGY -> ScalingEffect.ENERGY_DAMAGE_BONUS
    DamageType.MAGICAL -> null
}

private fun weaponProfileFor(
    weapon: Item?,
    instance: ItemInstance.Equipment?,
    balance: BalanceLookup,
): WeaponProfile {
    val damageType = weapon?.damageType ?: balance.unarmedDamageType()
    val basePower = weapon?.weaponPower ?: balance.unarmedWeaponPower()
    val weaponPower = if (weapon != null && instance != null) {
        (basePower * balance.rarityMultiplier(instance.rarity)).roundToInt().coerceAtLeast(0)
    } else {
        basePower
    }
    val combatSkill = weapon?.combatSkill ?: balance.unarmedCombatSkill()
    val range = weapon?.range ?: balance.unarmedRange()
    return WeaponProfile(damageType, weaponPower, combatSkill, range)
}
