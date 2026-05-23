package dev.gvart.genesara.world.environment.internal.mount

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.player.ScalingEffect
import dev.gvart.genesara.world.AgentItemInstancesStore
import dev.gvart.genesara.world.DamageType
import dev.gvart.genesara.world.EquipSlot
import dev.gvart.genesara.world.EquipmentBonusAggregator
import dev.gvart.genesara.world.ItemLookup
import dev.gvart.genesara.world.MountCatalog
import dev.gvart.genesara.world.MountInstanceStore
import dev.gvart.genesara.world.MountSlot
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.WorldRejection
import dev.gvart.genesara.world.commands.CombatCommand
import dev.gvart.genesara.world.events.EnvironmentEvent
import dev.gvart.genesara.world.events.MountDeathCause
import dev.gvart.genesara.world.events.WorldEvent
import dev.gvart.genesara.world.internal.balance.BalanceLookup
import dev.gvart.genesara.world.internal.worldstate.CrossZoneEffect
import dev.gvart.genesara.world.internal.worldstate.ReducerOutput
import dev.gvart.genesara.world.internal.worldstate.slices.EnvironmentSlice
import dev.gvart.genesara.world.internal.worldstate.views.BodyReadView
import dev.gvart.genesara.world.internal.worldstate.views.CoreReadView
import kotlin.random.Random

/**
 * Reducer for [CombatCommand.AttackMount] — mirrors `reduceAttackNpc` at a
 * lean shape. Read mount HP, charge attacker stamina, compute damage from
 * weapon + scaling + crit, mitigate by `mountDef.defense + bardingBonus`,
 * apply. On killing blow: `MountDied(cause = COMBAT, killedBy = attacker)` +
 * mount row delete; equipped MountGear destruction and cargo drop are
 * handled by the side-effect path in stage D's mount-death cleanup.
 *
 * Mounted attacker: no special bonus (decision from grilling — mount =
 * mobility, not a damage buff). Jousting (mount-vs-mount) is explicitly
 * out of scope.
 */
fun reduceAttackMount(
    environment: EnvironmentSlice,
    bodyView: BodyReadView,
    core: CoreReadView,
    command: CombatCommand.AttackMount,
    balance: BalanceLookup,
    items: ItemLookup,
    agents: AgentRegistry,
    equipment: AgentItemInstancesStore,
    equipmentBonuses: EquipmentBonusAggregator,
    mountCatalog: MountCatalog,
    mounts: MountInstanceStore,
    rng: Random,
    tick: Long,
): Either<WorldRejection, ReducerOutput<EnvironmentSlice>> = either {
    val attackerNode = ensureNotNull(core.positions[command.agent]) {
        WorldRejection.NotInWorld(command.agent)
    }
    val mount = ensureNotNull(mounts.findById(command.mount)) {
        WorldRejection.UnknownMount(command.agent, command.mount)
    }
    ensure(!mount.isDead) { WorldRejection.MountAlreadyDead(command.agent, command.mount) }
    ensure(mount.mountedByAgentId != command.agent) {
        WorldRejection.MountedActionNotAllowed(command.agent, "attack(self-mount)")
    }

    val weaponInstance = equipment.equippedFor(command.agent)[EquipSlot.MAIN_HAND]
    val weaponDef = weaponInstance?.let { items.byId(it.itemId) }
    val weaponPower = weaponDef?.weaponPower ?: balance.unarmedWeaponPower()
    val damageType = weaponDef?.damageType ?: balance.unarmedDamageType()
    val weaponRange = weaponDef?.range ?: balance.unarmedRange()

    val hops = hopDistance(core, attackerNode, mount.nodeId, weaponRange)
    ensure(hops in 0..weaponRange) {
        WorldRejection.MountNotAtSameNode(command.agent, command.mount, attackerNode, mount.nodeId)
    }

    val attackerBody = bodyView.bodyOf(command.agent)
        ?: error("Invariant violated: attacker ${command.agent} positioned without body")
    val staminaCost = balance.attackStaminaCost()
    ensure(attackerBody.stamina >= staminaCost) {
        WorldRejection.NotEnoughStamina(command.agent, staminaCost, attackerBody.stamina)
    }
    val attacker = agents.find(command.agent)
        ?: error("Invariant violated: attacker ${command.agent} positioned without registry row")

    val mountDef = mountCatalog.byType(mount.type)
        ?: error("Catalog corruption: mount ${command.mount} has type ${mount.type.value} which has no MountDef")
    val bardingBonus = equipment.let { store ->
        // Sum mountGearBonus across MountGear instances equipped to this mount
        // in the BARDING slot. Stage E owns the equip mechanism; we read what
        // it persisted. equipmentBonuses aggregator is unused here — its scope
        // is agent ScalingEffects, not mount stats.
        mountBardingBonus(mount.id, items, store)
    }
    val defense = (mountDef.defense + bardingBonus).coerceAtLeast(0)

    val attackerStat = balance.combatStatFor(
        weaponDef?.combatSkill ?: balance.unarmedCombatSkill(),
    ).valueOn(attacker.attributes)
    val rawDamage = attackerStat * weaponPower
    val mitigated = (rawDamage - defense).coerceAtLeast(0)
    val typed = (mitigated * balance.damageTypeModifier(damageType)).toInt().coerceAtLeast(0)

    // Mounts have no dodge (decision: keep AttackMount lean; dodge could be
    // added later as a mountDef field if needed).
    val critChance = balance.critChancePercent(attacker.attributes.luck) +
        equipmentBonuses.passiveBuff(command.agent, ScalingEffect.CRIT_CHANCE)
    val isCrit = rng.nextInt(100) < critChance
    val hpLost = if (isCrit) typed * balance.critMultiplier() else typed
    val nextMount = mount.takeDamage(hpLost)
    val killed = nextMount.isDead

    val effects = mutableListOf<CrossZoneEffect>(
        CrossZoneEffect.UpdateBody(command.agent, attackerBody.spendStamina(staminaCost)),
    )
    val events = mutableListOf<WorldEvent>()
    if (killed) {
        ensure(mounts.delete(mount.id)) { WorldRejection.MountAlreadyDead(command.agent, command.mount) }
        mount.mountedByAgentId?.let { rider ->
            events += EnvironmentEvent.TransportDismounted(
                agent = rider,
                mount = mount.id,
                mountType = mount.type,
                at = mount.nodeId,
                tick = tick,
                causedBy = command.commandId,
            )
        }
        events += EnvironmentEvent.MountDied(
            mount = mount.id,
            mountType = mount.type,
            owner = mount.ownerAgentId,
            at = mount.nodeId,
            cause = MountDeathCause.COMBAT,
            killedBy = command.agent,
            tick = tick,
            causedBy = command.commandId,
        )
    } else {
        ensure(mounts.update(nextMount)) { WorldRejection.MountAlreadyDead(command.agent, command.mount) }
    }
    events += EnvironmentEvent.AgentAttackedMount(
        attacker = command.agent,
        mount = mount.id,
        mountType = mount.type,
        at = mount.nodeId,
        damageType = damageType,
        baseDamage = typed,
        hpLost = hpLost,
        isCrit = isCrit,
        isDodged = false,
        mountHpAfter = nextMount.hpCurrent,
        mountKilled = killed,
        tick = tick,
        causedBy = command.commandId,
    )
    ReducerOutput(sliceDelta = environment, effects = effects, events = events.toList())
}

private fun hopDistance(core: CoreReadView, from: NodeId, to: NodeId, maxHops: Int): Int {
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

private fun mountBardingBonus(
    mountId: dev.gvart.genesara.world.MountId,
    items: ItemLookup,
    equipment: AgentItemInstancesStore,
): Int = equipment.byEquippedOnMount(mountId)
    .filter { it.equippedMountSlot == MountSlot.BARDING }
    .sumOf { items.byId(it.itemId)?.mountGearBonus ?: 0 }
