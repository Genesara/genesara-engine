package dev.gvart.genesara.world.combat.internal.abilities

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import dev.gvart.genesara.player.AbilityCostResource
import dev.gvart.genesara.player.AbilityEffectKind
import dev.gvart.genesara.player.AbilityTarget
import dev.gvart.genesara.player.ActivePerk
import dev.gvart.genesara.player.ActivePerkLookup
import dev.gvart.genesara.player.PerkCooldownStore
import dev.gvart.genesara.player.PerkEffect
import dev.gvart.genesara.player.SkillProgression
import dev.gvart.genesara.world.WorldRejection
import dev.gvart.genesara.world.commands.CombatCommand
import dev.gvart.genesara.world.events.CombatEvent
import dev.gvart.genesara.world.internal.balance.BalanceLookup
import dev.gvart.genesara.world.internal.behavior.ActionCategory
import dev.gvart.genesara.world.internal.behavior.BehaviorTracker
import dev.gvart.genesara.world.internal.body.AgentBody
import dev.gvart.genesara.world.internal.worldstate.ReducerOutput
import dev.gvart.genesara.world.internal.worldstate.slices.BodySlice
import dev.gvart.genesara.world.internal.worldstate.views.CoreReadView
import dev.gvart.genesara.world.internal.abilities.PendingAttackScaleStore

// Validation order pays the cheap reads first so a misuse short-circuits before
// we touch the cooldown row or charge a resource. The cooldown is armed BEFORE
// the resource is spent so a future spend that grows a precondition still leaves
// the cast committed — matches "cost paid at cast, not refunded" from spec §9.
//
// Effect kinds other than SCALE_NEXT_ATTACK ship the discriminator + params on
// [CombatEvent.AbilityUsed]; downstream resolvers attach in later slices,
// mirroring [dev.gvart.genesara.world.internal.perks.TriggeredPassiveDispatcher].
fun reduceUseAbility(
    body: BodySlice,
    core: CoreReadView,
    command: CombatCommand.UseAbility,
    activePerks: ActivePerkLookup,
    cooldowns: PerkCooldownStore,
    pendingScales: PendingAttackScaleStore,
    progression: SkillProgression,
    balance: BalanceLookup,
    behaviorTracker: BehaviorTracker,
    tickIntervalSeconds: Long,
    tick: Long,
): Either<WorldRejection, ReducerOutput<BodySlice>> = either {
    val casterNode = ensureNotNull(core.positions[command.agent]) {
        WorldRejection.NotInWorld(command.agent)
    }

    val active: ActivePerk = ensureNotNull(activePerks.byAbility(command.agent, command.ability)) {
        WorldRejection.UnknownAbility(command.agent, command.ability)
    }
    val effect: PerkEffect.ActiveAbility = active.effect

    when (effect.target) {
        AbilityTarget.SELF, AbilityTarget.AREA_SELF_NODE -> ensure(command.target == null) {
            WorldRejection.AbilityTargetMismatch(command.agent, command.ability, effect.target)
        }
        AbilityTarget.SINGLE_AGENT -> {
            val target = ensureNotNull(command.target) {
                WorldRejection.AbilityTargetMismatch(command.agent, command.ability, effect.target)
            }
            val targetNode = ensureNotNull(core.positions[target]) {
                WorldRejection.AbilityTargetNotInSameNode(command.agent, command.ability, target)
            }
            ensure(targetNode == casterNode) {
                WorldRejection.AbilityTargetNotInSameNode(command.agent, command.ability, target)
            }
        }
    }

    val until = cooldowns.readyAtTick(command.agent, active.perk.id) ?: 0L
    ensure(tick >= until) {
        WorldRejection.AbilityOnCooldown(
            agent = command.agent,
            ability = command.ability,
            readyAtTick = until,
        )
    }

    val currentBody = body.bodyOf(command.agent)
        ?: error("Invariant violated: agent ${command.agent} positioned but has no body")
    val available = currentBody.availableOf(effect.costResource)
    ensure(available >= effect.costAmount) {
        WorldRejection.InsufficientAbilityResource(
            agent = command.agent,
            ability = command.ability,
            resource = effect.costResource,
            required = effect.costAmount,
            available = available,
        )
    }

    val readyAtTick = tick + effect.cooldownTicks
    cooldowns.arm(command.agent, active.perk.id, readyAtTick, tick)

    val nextBody = currentBody.spend(effect.costResource, effect.costAmount)
    val nextSlice = body.copy(bodies = body.bodies + (command.agent to nextBody))

    if (effect.effectKind == AbilityEffectKind.SCALE_NEXT_ATTACK) {
        val multiplierPct = effect.effectParams["multiplierPct"]?.toIntOrNull()
            ?: error(
                "ActiveAbility ${effect.abilityId.value} declared SCALE_NEXT_ATTACK without a numeric " +
                    "multiplierPct param — PerksValidator should reject malformed params at startup.",
            )
        // Bound the buff at the cooldown so a logged-out caster can't pop it
        // hours later — closes the active-ability-buff-expiry gap from the
        // pre-#79 in-memory field.
        pendingScales.stage(
            command.agent,
            multiplierPct,
            ttlSeconds = effect.cooldownTicks * tickIntervalSeconds,
        )
    }

    progression.accrueXp(
        command.agent,
        active.perk.skill,
        balance.useAbilityXpDelta(),
        tick,
        command.commandId,
    )
    behaviorTracker.record(command.agent, ActionCategory.COMBAT, tick)

    val event = CombatEvent.AbilityUsed(
        agent = command.agent,
        perkId = active.perk.id,
        abilityId = effect.abilityId,
        target = command.target,
        effectKind = effect.effectKind,
        params = effect.effectParams,
        costResource = effect.costResource,
        costAmount = effect.costAmount,
        readyAtTick = readyAtTick,
        tick = tick,
        causedBy = command.commandId,
    )
    ReducerOutput(sliceDelta = nextSlice, events = listOf(event))
}

private fun AgentBody.availableOf(resource: AbilityCostResource): Int = when (resource) {
    AbilityCostResource.HP -> hp
    AbilityCostResource.STAMINA -> stamina
    AbilityCostResource.MANA -> mana
}

private fun AgentBody.spend(resource: AbilityCostResource, amount: Int): AgentBody = when (resource) {
    AbilityCostResource.HP -> takeDamage(amount)
    AbilityCostResource.STAMINA -> spendStamina(amount)
    AbilityCostResource.MANA -> spendMana(amount)
}
