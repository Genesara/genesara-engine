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
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.Npc
import dev.gvart.genesara.world.NpcCatalog
import dev.gvart.genesara.world.NpcDef
import dev.gvart.genesara.world.NpcId
import dev.gvart.genesara.world.WorldRejection
import dev.gvart.genesara.world.commands.CombatCommand
import dev.gvart.genesara.world.events.CombatEvent
import dev.gvart.genesara.world.events.EconomyEvent
import dev.gvart.genesara.world.events.EnvironmentEvent
import dev.gvart.genesara.world.events.WorldEvent
import dev.gvart.genesara.world.internal.abilities.PendingAttackScaleStore
import dev.gvart.genesara.world.internal.balance.BalanceLookup
import dev.gvart.genesara.world.internal.behavior.ActionCategory
import dev.gvart.genesara.world.internal.behavior.BehaviorTracker
import dev.gvart.genesara.world.internal.worldstate.CrossZoneEffect
import dev.gvart.genesara.world.internal.worldstate.ReducerOutput
import dev.gvart.genesara.world.internal.worldstate.slices.EnvironmentSlice
import dev.gvart.genesara.world.internal.worldstate.views.BodyReadView
import dev.gvart.genesara.world.internal.worldstate.views.CoreReadView
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Reducer for [CombatCommand.AttackNpc] — the agent-vs-NPC parallel of
 * [dev.gvart.genesara.world.internal.combat.reduceAttack]. Reads agent-side
 * scaling/aura/class/equipment exactly like the agent-vs-agent path, but
 * resolves defender mitigation against the catalog's flat
 * [NpcDef.defense] and dodge against [NpcDef.dodgeChancePercent] instead
 * of attribute-driven values.
 *
 * On the killing blow:
 *  - rolls loot via [LootRoll] and deposits drops to the ground,
 *  - emits [EnvironmentEvent.NpcDied] with the drops list + one
 *    [EconomyEvent.ItemDroppedOnGround] per drop (mirror of the agent-death
 *    pattern in `DeathProcessor.applyDeath`),
 *  - awards the kill XP bonus on top of the per-swing XP,
 *  - removes the NPC from environment slice which advances
 *    `nodesClearedThisTick` when the node empties.
 */
fun reduceAttackNpc(
    environment: EnvironmentSlice,
    bodyView: BodyReadView,
    core: CoreReadView,
    command: CombatCommand.AttackNpc,
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
): Either<WorldRejection, ReducerOutput<EnvironmentSlice>> = either {
    val attackerNode = ensureNotNull(core.positions[command.agent]) {
        WorldRejection.NotInWorld(command.agent)
    }
    val npc = ensureNotNull(environment.npcs[command.npc]) {
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

    val hops = hopDistanceCore(core, attackerNode, npc.nodeId, weaponProfile.range)
    ensure(hops in 0..weaponProfile.range) {
        WorldRejection.NpcOutOfRange(
            command.agent, command.npc, attackerNode, npc.nodeId, weaponProfile.range,
        )
    }

    val attackerBody = bodyView.bodyOf(command.agent)
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

    val effects = mutableListOf<CrossZoneEffect>(
        CrossZoneEffect.UpdateBody(command.agent, nextAttackerBody),
    )
    val nextEnv = if (killed) {
        removeNpcFromSlice(environment, npc, tick)
    } else {
        updateNpcInSlice(environment, nextNpc)
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
        progression.accrueXp(
            command.agent, HUNTING_SKILL, balance.huntingKillXp(),
            tick, command.commandId, attacker.classId,
        )
    }
    behaviorTracker.record(command.agent, ActionCategory.COMBAT, tick)

    val events = mutableListOf<WorldEvent>()
    events += CombatEvent.AgentAttackedNpc(
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

    var resultEnv = nextEnv
    if (killed) {
        val killerLevel = attacker.level
        // LOOT_QUALITY_BONUS is a fractional [0..1] shift toward max quantity — `scaling.bonusFor` is
        // the right shape (Double, 0..1). `passiveAura.bonusFor` returns flat int units which would
        // mean a +1 aura jumps the multiplier to +100% (always max). No perk emits a LOOT_QUALITY_BONUS
        // aura today; if one ships later, define its semantic (flat-units vs fractional) before
        // re-combining here.
        val huntingLootBonus = scaling.bonusFor(command.agent, ScalingEffect.LOOT_QUALITY_BONUS)
        val drops = lootRoll.rollAndDeposit(
            npcType = npc.type,
            node = npc.nodeId,
            killerCombatSkillLevel = killerLevel,
            killerLuck = attacker.attributes.luck,
            huntingLootBonus = huntingLootBonus,
            tick = tick,
            rng = rng,
        )
        events += EnvironmentEvent.NpcDied(
            npc = npc.id,
            npcType = npc.type,
            at = npc.nodeId,
            killedBy = command.agent,
            drops = drops,
            tick = tick,
            causedBy = command.commandId,
        )
        for (drop in drops) {
            events += EconomyEvent.ItemDroppedOnGround(
                at = npc.nodeId,
                byAgent = command.agent,
                drop = drop,
                tick = tick,
                causedBy = command.commandId,
            )
        }
    } else if (def.aggressionProfile == AggressionProfile.PASSIVE) {
        // PASSIVE flee inline (Q15b α): pick a random node within `fleeDistance`
        // hops, excluding the attacker's node and the NPC's current node. If
        // none reachable, the NPC stands and dies later.
        val candidates = fleeCandidatesCore(core, npc.nodeId, attackerNode, def.fleeDistance)
        if (candidates.isNotEmpty()) {
            val destination = candidates.elementAt(rng.nextInt(candidates.size))
            val moved = nextNpc.moveTo(destination)
            resultEnv = updateNpcInSlice(resultEnv, moved)
            events += EnvironmentEvent.NpcMoved(
                npc = npc.id,
                npcType = npc.type,
                from = npc.nodeId,
                to = destination,
                tick = tick,
            )
        }
    }

    ReducerOutput(sliceDelta = resultEnv, effects = effects, events = events.toList())
}

private val HUNTING_SKILL = SkillId("HUNTING")

private fun updateNpcInSlice(env: EnvironmentSlice, npc: Npc): EnvironmentSlice =
    env.copy(
        npcs = env.npcs + (npc.id to npc),
        dirtyNpcs = env.dirtyNpcs + npc.id,
    )

/**
 * Slice-local mirror of `WorldState.removeNpc`. When [npc] was the last NPC at its
 * node, advance `nodesClearedThisTick` so the lazy-spawn timer starts counting down
 * from the moment the node actually emptied.
 */
private fun removeNpcFromSlice(env: EnvironmentSlice, npc: Npc, tick: Long): EnvironmentSlice {
    val nextNpcs = env.npcs - npc.id
    val sameNodeRemaining = nextNpcs.values.any { it.nodeId == npc.nodeId }
    val cleared = if (!sameNodeRemaining) {
        env.nodesClearedThisTick + (npc.nodeId to tick)
    } else {
        env.nodesClearedThisTick
    }
    return env.copy(
        npcs = nextNpcs,
        removedNpcs = env.removedNpcs + npc.id,
        dirtyNpcs = env.dirtyNpcs - npc.id,
        nodesClearedThisTick = cleared,
    )
}

/**
 * BFS the node graph from [origin] out to [maxHops] (≥1) and return every node
 * reachable within that radius, excluding [origin] and [attackerNode]. Avoidance
 * semantics: paths cannot route *through* [attackerNode] — the attacker's node
 * is poisoned as if a wall, so a PASSIVE mob with a single escape route blocked
 * by its attacker has no fleeCandidates and stands its ground. Result is sorted
 * by node id for deterministic ordering across runs.
 */
private fun fleeCandidatesCore(
    core: CoreReadView,
    origin: NodeId,
    attackerNode: NodeId,
    maxHops: Int,
): List<NodeId> {
    val cap = maxHops.coerceAtLeast(1)
    val visited = mutableSetOf(origin, attackerNode)
    val reached = mutableListOf<NodeId>()
    var frontier: Set<NodeId> = setOf(origin)
    repeat(cap) {
        val next = mutableSetOf<NodeId>()
        for (nodeId in frontier) {
            val node = core.nodes[nodeId] ?: continue
            for (neighbor in node.adjacency) {
                if (visited.add(neighbor)) {
                    next += neighbor
                    reached += neighbor
                }
            }
        }
        if (next.isEmpty()) return reached.sortedBy { it.value }
        frontier = next
    }
    return reached.sortedBy { it.value }
}

private fun hopDistanceCore(core: CoreReadView, from: NodeId, to: NodeId, maxHops: Int): Int {
    if (from == to) return 0
    if (maxHops <= 0) return -1
    val visited = mutableSetOf(from)
    var frontier: Set<NodeId> = setOf(from)
    for (depth in 1..maxHops) {
        val next = mutableSetOf<NodeId>()
        for (nodeId in frontier) {
            val node = core.nodes[nodeId] ?: continue
            for (neighbor in node.adjacency) {
                if (neighbor == to) return depth
                if (visited.add(neighbor)) next += neighbor
            }
        }
        if (next.isEmpty()) return -1
        frontier = next
    }
    return -1
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
