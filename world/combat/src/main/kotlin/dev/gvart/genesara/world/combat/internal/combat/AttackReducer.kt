package dev.gvart.genesara.world.combat.internal.combat

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.player.ClassLookup
import dev.gvart.genesara.player.LevelScalingAggregator
import dev.gvart.genesara.player.PassiveAuraAggregator
import dev.gvart.genesara.player.RelationshipsGateway
import dev.gvart.genesara.player.ScalingEffect
import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.player.SkillProgression
import dev.gvart.genesara.player.TriggeredPassiveTrigger
import dev.gvart.genesara.world.AgentItemInstancesStore
import dev.gvart.genesara.world.DamageType
import dev.gvart.genesara.world.EquipSlot
import dev.gvart.genesara.world.EquipmentBonusAggregator
import dev.gvart.genesara.world.Item
import dev.gvart.genesara.world.ItemInstance
import dev.gvart.genesara.world.ItemLookup
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.WorldRejection
import dev.gvart.genesara.world.commands.CombatCommand
import dev.gvart.genesara.world.events.BodyEvent
import dev.gvart.genesara.world.events.CombatEvent
import dev.gvart.genesara.world.events.WorldEvent
import dev.gvart.genesara.world.internal.abilities.PendingAttackScaleStore
import dev.gvart.genesara.world.internal.balance.BalanceLookup
import dev.gvart.genesara.world.internal.behavior.ActionCategory
import dev.gvart.genesara.world.internal.behavior.BehaviorTracker
import dev.gvart.genesara.world.internal.death.AttackCause
import dev.gvart.genesara.world.internal.death.DeathProcessor
import dev.gvart.genesara.world.internal.perks.TriggerContext
import dev.gvart.genesara.world.internal.perks.TriggeredPassiveDispatcher
import dev.gvart.genesara.world.internal.worldstate.CrossZoneEffect
import dev.gvart.genesara.world.internal.worldstate.ReducerOutput
import dev.gvart.genesara.world.internal.worldstate.slices.CombatSlice
import dev.gvart.genesara.world.internal.worldstate.views.BodyReadView
import dev.gvart.genesara.world.internal.worldstate.views.CoreReadView
import dev.gvart.genesara.world.internal.worldstate.views.EnvironmentReadView
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Reducer for [CombatCommand.AttackTarget] (ADR 0003 §P4 slice shape). Owns the
 * [CombatSlice] (rolling kill-streak window); reads body / core / environment
 * through their typed views.
 *
 *   damage = attackerStat × weaponPower (armor mitigation by defender CON × armorDef)
 *   dodge first; on dodge → hpLost = 0. Else crit; on crit → hpLost = base × critMultiplier.
 *
 * RNG is injected so tests can pass a seeded [Random] to pin crit/dodge outcomes.
 *
 * Killing-blow propagation: when post-damage HP hits 0, the reducer calls
 * [DeathProcessor.computeDeath] which returns the slice writes (UpdateInventory,
 * UpdateKillStreak/IncrementKillStreak, RemovePosition) + events for the
 * dispatcher's `applyEffects` pass. External side-effects (GroundItemStore,
 * equipment instance deletes) fire inside `computeDeath` directly since they
 * are not slice writes.
 *
 * TODO(combat-durability): weapons are not consumed on attack in Slice 1. Land
 * with the durability slice (separate combat issue).
 */
fun reduceAttack(
    combat: CombatSlice,
    bodyView: BodyReadView,
    coreView: CoreReadView,
    @Suppress("UNUSED_PARAMETER") envView: EnvironmentReadView,
    command: CombatCommand.AttackTarget,
    balance: BalanceLookup,
    items: ItemLookup,
    agents: AgentRegistry,
    equipment: AgentItemInstancesStore,
    progression: SkillProgression,
    scaling: LevelScalingAggregator,
    passiveAura: PassiveAuraAggregator,
    equipmentBonuses: EquipmentBonusAggregator,
    deathProcessor: DeathProcessor,
    triggeredPassives: TriggeredPassiveDispatcher,
    pendingScales: PendingAttackScaleStore,
    behaviorTracker: BehaviorTracker,
    relationships: RelationshipsGateway = RelationshipsGateway.NoOp,
    rng: Random,
    tick: Long,
    classes: ClassLookup = dev.gvart.genesara.player.NoOpClassLookup,
): Either<WorldRejection, ReducerOutput<CombatSlice>> = either {
    ensure(command.agent != command.target) { WorldRejection.CannotAttackSelf(command.agent) }

    val attackerNode = ensureNotNull(coreView.positions[command.agent]) {
        WorldRejection.NotInWorld(command.agent)
    }
    val targetNode = ensureNotNull(coreView.positions[command.target]) {
        WorldRejection.TargetNotInWorld(command.agent, command.target)
    }

    val weaponInstance = equipment.equippedFor(command.agent)[EquipSlot.MAIN_HAND]
    val weaponDef = weaponInstance?.let { items.byId(it.itemId) }
    val weaponProfile = weaponProfileFor(weaponDef, weaponInstance, balance)

    ensure(isWithinRange(coreView, attackerNode, targetNode, weaponProfile.range)) {
        WorldRejection.TargetOutOfRange(
            command.agent, command.target, attackerNode, targetNode, weaponProfile.range,
        )
    }

    val targetBody = bodyView.bodyOf(command.target)
        ?: error("Invariant violated: target ${command.target} positioned but has no body")
    ensure(targetBody.hp > 0) { WorldRejection.TargetAlreadyDead(command.agent, command.target) }

    val attackerBody = bodyView.bodyOf(command.agent)
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
    // Lore §9 armor mitigation: `mitigation = targetStat × armorDef`, where targetStat
    // is the defender's CONSTITUTION. Subtracts from raw damage before the type modifier
    // multiplies. armorDef is summed across every equipped piece with an
    // [EquippedBonus.ArmorDef] entry matching the weapon's damage type. ADR-0001
    // documents the choice of base CON (not effective CON) for the multiplier so the
    // value cannot bootstrap recursively via equipped attribute bonuses.
    val armorDef = equipmentBonuses.armorDef(command.target, weaponProfile.damageType)
    val mitigation = defender.attributes.constitution * armorDef
    val mitigatedRaw = (rawDamage - mitigation).coerceAtLeast(0)
    val typedDamage = (mitigatedRaw * balance.damageTypeModifier(weaponProfile.damageType))
        .toInt()
        .coerceAtLeast(0)
    val scalingEffect = scalingEffectFor(weaponProfile.damageType)
    val damageScaling = scalingEffect?.let { scaling.bonusFor(command.agent, it) } ?: 0.0
    // PassiveAura is a flat post-scaling term: scaling multiplies the typed base, then
    // chosen auras add on top. Order keeps "+5 Sharpen Edge" predictable regardless of
    // SWORD level, while "Doubled Edge" still doubles the per-level rate underneath.
    val auraBonus = scalingEffect?.let { passiveAura.bonusFor(command.agent, it) } ?: 0
    // Class outgoing-damage multiplier composes LAST — it scales the resolved
    // (typed × per-level scaling + aura) base. Applying it earlier would
    // double-multiply with `damageScaling` and silently shift the spec's "flat
    // bonus" semantics into a compound one.
    val classMod = classes.damageMultiplier(attacker.classId, weaponProfile.damageType.name)
    val preClassScaled = ((typedDamage * (1.0 + damageScaling)).toInt() + auraBonus).coerceAtLeast(0)
    val baseScaled = (preClassScaled * classMod).toInt().coerceAtLeast(0)
    // Read-and-clear before the rolls so a dodge still burns the staged buff —
    // matches "cost paid at cast, not refunded on miss" from spec §9.
    val pendingScalePct = pendingScales.consume(command.agent)
    val scaledDamage = if (pendingScalePct != null) {
        (baseScaled.toLong() * pendingScalePct / 100).toInt().coerceAtLeast(0)
    } else {
        baseScaled
    }

    // Dodge rolls FIRST so a successful dodge short-circuits the crit roll. Otherwise a crit
    // followed by a dodge would burn the RNG cursor on a discarded crit and shift downstream
    // rolls — keeping order matters for reproducible seeds.
    val dodgeChance = balance.dodgeChancePercent(defender.attributes.dexterity) +
        equipmentBonuses.passiveBuff(command.target, ScalingEffect.DODGE_CHANCE)
    val isDodged = rng.nextInt(100) < dodgeChance
    val (hpLost, isCrit) = if (isDodged) {
        0 to false
    } else {
        // Equipped CRIT_CHANCE bonuses (e.g. GEM_RING) add percentage points on top
        // of the LUCK-derived base. Same shape as DODGE_CHANCE on the defender side.
        val critChance = balance.critChancePercent(attacker.attributes.luck) +
            equipmentBonuses.passiveBuff(command.agent, ScalingEffect.CRIT_CHANCE)
        val crit = rng.nextInt(100) < critChance
        val landed = if (crit) scaledDamage * balance.critMultiplier() else scaledDamage
        landed to crit
    }

    val nextTargetBody = targetBody.takeDamage(hpLost)
    val nextAttackerBody = attackerBody.spendStamina(staminaCost)

    val effects = mutableListOf<CrossZoneEffect>(
        CrossZoneEffect.UpdateBody(command.target, nextTargetBody),
        CrossZoneEffect.UpdateBody(command.agent, nextAttackerBody),
    )

    progression.accrueXp(command.agent, weaponProfile.combatSkill, balance.attackXpDelta(), tick, command.commandId, attacker.classId)
    behaviorTracker.record(command.agent, ActionCategory.COMBAT, tick)

    val attackEvent = CombatEvent.AgentAttacked(
        attacker = command.agent,
        target = command.target,
        at = attackerNode,
        damageType = weaponProfile.damageType,
        baseDamage = scaledDamage,
        hpLost = hpLost,
        isCrit = isCrit,
        isDodged = isDodged,
        targetHpAfter = nextTargetBody.hp,
        targetKilled = nextTargetBody.hp == 0,
        tick = tick,
        causedBy = command.commandId,
    )

    val emitted = mutableListOf<WorldEvent>(attackEvent)
    val attackerCombatCtx = TriggerContext.Combat(target = command.target)
    val defenderCombatCtx = TriggerContext.Combat(target = command.agent)
    if (isDodged) {
        emitted += triggeredPassives.dispatch(
            firer = command.target,
            trigger = TriggeredPassiveTrigger.ON_DODGE,
            ctx = defenderCombatCtx,
            tick = tick,
            causedBy = command.commandId,
        )
    } else {
        emitted += triggeredPassives.dispatch(
            firer = command.agent,
            trigger = TriggeredPassiveTrigger.ON_HIT_DEALT,
            ctx = attackerCombatCtx,
            tick = tick,
            causedBy = command.commandId,
        )
        emitted += triggeredPassives.dispatch(
            firer = command.target,
            trigger = TriggeredPassiveTrigger.ON_HIT_TAKEN,
            ctx = defenderCombatCtx,
            tick = tick,
            causedBy = command.commandId,
        )
        if (isCrit) {
            emitted += triggeredPassives.dispatch(
                firer = command.agent,
                trigger = TriggeredPassiveTrigger.ON_CRIT,
                ctx = attackerCombatCtx,
                tick = tick,
                causedBy = command.commandId,
            )
        }
        // OnLowHp fires even on the killing blow so a "second wind" perk surfaces
        // alongside the death — order in the stream is `OnHitTaken → OnLowHp → death`.
        emitted += triggeredPassives.dispatch(
            firer = command.target,
            trigger = TriggeredPassiveTrigger.ON_LOW_HP,
            ctx = TriggerContext.HpChange(
                maxHp = targetBody.maxHp,
                prevHp = targetBody.hp,
                newHp = nextTargetBody.hp,
            ),
            tick = tick,
            causedBy = command.commandId,
        )
    }

    if (nextTargetBody.hp == 0) {
        val deathOutcome = deathProcessor.computeDeath(
            victimKillStreak = combat.killStreakOf(command.target),
            victimInventory = bodyView.inventoryOf(command.target),
            agentId = command.target,
            deathNode = targetNode,
            cause = AttackCause(commandId = command.commandId, attackerId = command.agent),
            tick = tick,
            rng = rng,
        )
        if (deathOutcome != null) {
            effects += deathOutcome.effects
            emitted += deathOutcome.events
        } else {
            // Registry returned no penalty outcome — mirror applyDeath's fallback: clear
            // the dying agent's position without emitting AgentDied.
            effects += CrossZoneEffect.RemovePosition(command.target)
        }
        // Kill-bonus XP on top of the per-swing XP. Mirrors the NPC kill bonus
        // in AttackNpcReducer so both kill paths reward the killer through the
        // same combat skill the killing blow trained.
        progression.accrueXp(
            command.agent, weaponProfile.combatSkill, balance.agentKillXpBonus(),
            tick, command.commandId, attacker.classId,
        )
        emitted += triggeredPassives.dispatch(
            firer = command.agent,
            trigger = TriggeredPassiveTrigger.ON_KILL,
            ctx = attackerCombatCtx,
            tick = tick,
            causedBy = command.commandId,
        )
    }

    applyWitnessCascade(
        coreView = coreView,
        attacker = command.agent,
        victim = command.target,
        victimFame = defender.fame,
        attackerNode = attackerNode,
        killed = nextTargetBody.hp == 0,
        balance = balance,
        relationships = relationships,
        tick = tick,
    )

    ReducerOutput(sliceDelta = combat, effects = effects.toList(), events = emitted.toList())
}

/**
 * Mechanics-reference §11 witness cascade: every other agent co-located at
 * [attackerNode] who can perceive the event (Phase 2 = same-node only; LOS
 * extension is deferred) gets a per-pair relationship drop against the
 * [attacker]. Suppressed entirely when [victimFame] is below
 * `BalanceLookup.fameWitnessProtectionThreshold` — a low-Fame "nobody" can
 * be attacked without social cost (§19).
 *
 * The cascade is a pure side-effect on the relationships gateway; the
 * attack reducer's slice transition is unaffected. Read by the
 * trust-gate in TradeReducer and (eventually) the outlaw state machine.
 */
private fun applyWitnessCascade(
    coreView: CoreReadView,
    attacker: AgentId,
    victim: AgentId,
    victimFame: Int,
    attackerNode: NodeId,
    killed: Boolean,
    balance: BalanceLookup,
    relationships: RelationshipsGateway,
    tick: Long,
) {
    if (victimFame < balance.fameWitnessProtectionThreshold()) return
    val delta = if (killed) balance.relationshipDeltaOnKillWitnessed() else balance.relationshipDeltaOnAttackWitnessed()
    if (delta == 0) return
    // Snapshot from the pre-death positions so a witness who died on this same tick
    // still counts (their relationships don't unwind retroactively). Batched upsert
    // collapses N witnesses into one round-trip; a 30-agent node otherwise costs 30.
    val witnesses = coreView.positions
        .filter { (witness, node) -> node == attackerNode && witness != attacker && witness != victim }
        .keys
    if (witnesses.isEmpty()) return
    relationships.adjustMany(attacker, witnesses, delta, tick)
}

private data class WeaponProfile(
    val damageType: DamageType,
    val weaponPower: Int,
    val combatSkill: SkillId,
    val range: Int,
)

/**
 * Damage types unmapped here (e.g. [DamageType.MAGICAL]) have no corresponding
 * scaling effect in the v1 enum (`docs/skill-feature-sequence.md` issue #66 pin) —
 * a magical attack scales only by armor/resists and gets no per-level skill bonus
 * until a magic-class slice extends the enum.
 */
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

/**
 * BFS over [CoreReadView.nodes] adjacency, capped at [range] hops, looking for
 * [target] from [from]. Range 1 = same-node only (no hops). Range 2 = same node
 * or any direct neighbor. Higher ranges follow the adjacency graph further.
 * Stops as soon as the target is reachable, so the worst case is one full
 * `range`-hop expansion of the starting node's neighborhood.
 */
private fun isWithinRange(coreView: CoreReadView, from: NodeId, target: NodeId, range: Int): Boolean {
    if (from == target) return true
    if (range <= 1) return false
    val visited = mutableSetOf(from)
    var frontier: Set<NodeId> = setOf(from)
    repeat(range - 1) {
        val next = mutableSetOf<NodeId>()
        for (nodeId in frontier) {
            val node = coreView.nodes[nodeId] ?: continue
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
