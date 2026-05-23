package dev.gvart.genesara.world.environment.internal.mount

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.player.AgentSkillsRegistry
import dev.gvart.genesara.player.LevelScalingAggregator
import dev.gvart.genesara.player.PassiveAuraAggregator
import dev.gvart.genesara.player.ScalingEffect
import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.player.SkillProgression
import dev.gvart.genesara.world.Mount
import dev.gvart.genesara.world.MountCatalog
import dev.gvart.genesara.world.MountId
import dev.gvart.genesara.world.MountInstanceStore
import dev.gvart.genesara.world.Npc
import dev.gvart.genesara.world.NpcId
import dev.gvart.genesara.world.WorldRejection
import dev.gvart.genesara.world.commands.EnvironmentCommand
import dev.gvart.genesara.world.events.EnvironmentEvent
import dev.gvart.genesara.world.events.WorldEvent
import dev.gvart.genesara.world.internal.balance.BalanceLookup
import dev.gvart.genesara.world.internal.behavior.ActionCategory
import dev.gvart.genesara.world.internal.behavior.BehaviorTracker
import dev.gvart.genesara.world.internal.worldstate.CrossZoneEffect
import dev.gvart.genesara.world.internal.worldstate.ReducerOutput
import dev.gvart.genesara.world.internal.worldstate.slices.EnvironmentSlice
import dev.gvart.genesara.world.internal.worldstate.views.BodyReadView
import dev.gvart.genesara.world.internal.worldstate.views.CoreReadView
import java.util.UUID
import kotlin.random.Random

internal val ANIMAL_HANDLING_SKILL = SkillId("ANIMAL_HANDLING")

/**
 * Reducer for [EnvironmentCommand.Tame]. One stamina-priced roll per call;
 * success consumes the NPC row + inserts a Mount (world-owned, no per-agent owner); failure
 * may also spook the NPC into fleeing.
 *
 * Chance formula (mirrors AttackNpcReducer's damage shape, clamped 5..95):
 *   `chance = base × (1 + levelScaling[MOUNT_TAMING_BONUS]) + auraFlat + luck/2`
 *
 * ANIMAL_HANDLING XP accrues on every attempt (success or fail). When the
 * skill is unslotted, `progression.accrueXp` no-ops the XP grant but still
 * fires `AgentEvent.SkillRecommended` via `maybeRecommend` — that's how the
 * issue's recommendation hook surfaces without a dedicated rejection path.
 */
fun reduceTame(
    environment: EnvironmentSlice,
    bodyView: BodyReadView,
    core: CoreReadView,
    command: EnvironmentCommand.Tame,
    balance: BalanceLookup,
    agents: AgentRegistry,
    skills: AgentSkillsRegistry,
    scaling: LevelScalingAggregator,
    passiveAura: PassiveAuraAggregator,
    catalog: MountCatalog,
    mounts: MountInstanceStore,
    progression: SkillProgression,
    behaviorTracker: BehaviorTracker,
    rng: Random,
    tick: Long,
): Either<WorldRejection, ReducerOutput<EnvironmentSlice>> = either {
    val agentNode = ensureNotNull(core.positions[command.agent]) {
        WorldRejection.NotInWorld(command.agent)
    }
    val npc = ensureNotNull(environment.npcs[command.target]) {
        WorldRejection.UnknownNpc(command.agent, command.target)
    }
    ensure(npc.hpCurrent > 0) {
        WorldRejection.NpcAlreadyDead(command.agent, command.target)
    }
    ensure(npc.nodeId == agentNode) {
        WorldRejection.TameTargetNotAtSameNode(command.agent, command.target, agentNode, npc.nodeId)
    }
    val riding = mounts.findByRider(command.agent)
    ensure(riding == null) { WorldRejection.MountedActionNotAllowed(command.agent, "tame") }

    val mountDef = ensureNotNull(catalog.byTamedFromNpc(npc.type)) {
        WorldRejection.NpcNotTameable(command.agent, command.target)
    }

    val body = bodyView.bodyOf(command.agent)
        ?: error("Invariant violated: agent ${command.agent} positioned without a body row")
    val staminaCost = balance.tameStaminaCost()
    ensure(body.stamina >= staminaCost) {
        WorldRejection.NotEnoughStamina(command.agent, staminaCost, body.stamina)
    }

    val agent = agents.find(command.agent)
        ?: error("Invariant violated: agent ${command.agent} positioned without a registry row")

    val levelScaling = scaling.bonusFor(command.agent, ScalingEffect.MOUNT_TAMING_BONUS)
    val auraFlat = passiveAura.bonusFor(command.agent, ScalingEffect.MOUNT_TAMING_BONUS)
    val luckTerm = agent.attributes.luck / 2
    val rawChance = (mountDef.tameability * (1.0 + levelScaling)).toInt() + auraFlat + luckTerm
    val chance = rawChance.coerceIn(balance.tameMinChancePercent(), balance.tameMaxChancePercent())
    val roll = rng.nextInt(100)
    val success = roll < chance

    val events = mutableListOf<WorldEvent>()
    val effects = mutableListOf<CrossZoneEffect>(
        CrossZoneEffect.UpdateBody(command.agent, body.spendStamina(staminaCost)),
    )
    progression.accrueXp(
        command.agent, ANIMAL_HANDLING_SKILL, balance.tameAttemptXp(),
        tick, command.commandId, agent.classId,
    )
    behaviorTracker.record(command.agent, ActionCategory.GATHER, tick)

    val nextEnv = if (success) {
        progression.accrueXp(
            command.agent, ANIMAL_HANDLING_SKILL, balance.tameSuccessXp(),
            tick, command.commandId, agent.classId,
        )
        val mountId = MountId(UUID.randomUUID())
        val newMount = Mount(
            id = mountId,
            type = mountDef.type,
            nodeId = npc.nodeId,
            hpCurrent = mountDef.hpMax,
            hpMax = mountDef.hpMax,
            hunger = mountDef.hungerMax,
            hungerMax = mountDef.hungerMax,
            fatigue = mountDef.fatigueMax,
            fatigueMax = mountDef.fatigueMax,
            mountedByAgentId = null,
            tamedAtTick = tick,
        )
        mounts.insert(newMount)
        events += EnvironmentEvent.MountTamed(
            agent = command.agent,
            npc = command.target,
            mount = mountId,
            mountType = mountDef.type,
            at = npc.nodeId,
            rolledChancePercent = chance,
            tick = tick,
            causedBy = command.commandId,
        )
        consumeNpcFromSlice(environment, npc, tick)
    } else {
        val spookChance = balance.mountSpookChancePercent()
        val spookHit = spookChance > 0 && rng.nextInt(100) < spookChance
        val (envAfterFlee, spooked) = if (spookHit) {
            val candidates = dev.gvart.genesara.world.internal.movement.fleeCandidates(
                core, npc.nodeId, agentNode, balance.tameSpookFleeDistance(),
            )
            if (candidates.isNotEmpty()) {
                val destination = candidates.elementAt(rng.nextInt(candidates.size))
                val moved = npc.moveTo(destination)
                events += EnvironmentEvent.NpcMoved(
                    npc = npc.id,
                    npcType = npc.type,
                    from = npc.nodeId,
                    to = destination,
                    tick = tick,
                )
                updateNpcInSlice(environment, moved) to true
            } else {
                environment to false
            }
        } else {
            environment to false
        }
        events += EnvironmentEvent.MountTameFailed(
            agent = command.agent,
            npc = command.target,
            at = npc.nodeId,
            rolledChancePercent = chance,
            spooked = spooked,
            tick = tick,
            causedBy = command.commandId,
        )
        envAfterFlee
    }

    ReducerOutput(sliceDelta = nextEnv, effects = effects, events = events.toList())
}

private fun updateNpcInSlice(env: EnvironmentSlice, npc: Npc): EnvironmentSlice =
    env.copy(
        npcs = env.npcs + (npc.id to npc),
        dirtyNpcs = env.dirtyNpcs + npc.id,
    )

/**
 * Slice-local mirror of `AttackNpcReducer.removeNpcFromSlice`. When [npc] was
 * the last NPC at its node, advance `nodesClearedThisTick` so the lazy-spawn
 * timer starts counting from the moment the node actually emptied — same
 * invariant the combat-kill path maintains.
 */
private fun consumeNpcFromSlice(env: EnvironmentSlice, npc: Npc, tick: Long): EnvironmentSlice {
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

