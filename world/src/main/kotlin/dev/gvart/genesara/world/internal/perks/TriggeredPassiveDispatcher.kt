package dev.gvart.genesara.world.internal.perks

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.PerkCooldownStore
import dev.gvart.genesara.player.PerkId
import dev.gvart.genesara.player.TriggeredPassiveLookup
import dev.gvart.genesara.player.TriggeredPassiveTrigger
import dev.gvart.genesara.player.TriggeredPerk
import dev.gvart.genesara.world.EquipmentSetTriggerLookup
import dev.gvart.genesara.world.events.CombatEvent
import dev.gvart.genesara.world.events.WorldEvent
import java.util.UUID
import org.springframework.stereotype.Component

sealed interface TriggerContext {
    data object None : TriggerContext

    data class Combat(val target: AgentId) : TriggerContext

    data class HpChange(val maxHp: Int, val prevHp: Int, val newHp: Int) : TriggerContext
}

// TODO(perk-effects): slice 1 stops at PerkTriggered emission. Per-effect-kind
// resolvers (HEAL_SELF, APPLY_STATUS_TO_TARGET, DEAL_BONUS_DAMAGE, …) attach to
// the event in follow-up slices.
interface TriggeredPassiveDispatcher {
    fun dispatch(
        firer: AgentId,
        trigger: TriggeredPassiveTrigger,
        ctx: TriggerContext,
        tick: Long,
        causedBy: UUID?,
    ): List<WorldEvent>
}

@Component
internal class TriggeredPassiveDispatcherImpl(
    private val lookup: TriggeredPassiveLookup,
    private val setTriggers: EquipmentSetTriggerLookup,
    private val cooldowns: PerkCooldownStore,
) : TriggeredPassiveDispatcher {

    override fun dispatch(
        firer: AgentId,
        trigger: TriggeredPassiveTrigger,
        ctx: TriggerContext,
        tick: Long,
        causedBy: UUID?,
    ): List<WorldEvent> {
        val perkCandidates = lookup.matching(firer, trigger)
        val setCandidates = setTriggers.matching(firer, trigger)
        if (perkCandidates.isEmpty() && setCandidates.isEmpty()) return emptyList()

        val emitted = mutableListOf<WorldEvent>()
        for (candidate in perkCandidates) {
            if (!shouldFirePerk(candidate, ctx)) continue
            if (!cooldowns.isReady(firer, candidate.perk.id, tick)) continue

            cooldowns.arm(firer, candidate.perk.id, tick + candidate.effect.internalCooldownTicks, tick)
            emitted += CombatEvent.PerkTriggered(
                agent = firer,
                perkId = candidate.perk.id,
                trigger = trigger,
                effectKind = candidate.effect.effectKind,
                params = candidate.effect.params,
                target = (ctx as? TriggerContext.Combat)?.target,
                tick = tick,
                causedBy = causedBy,
            )
        }
        // Set-bonus triggers share the dispatcher loop but use synthetic perk ids
        // for cooldown keying + event emission (ADR-0002). Same PerkTriggered event
        // surface, so subscribers don't need a new variant — the `perkId` field's
        // `set:` prefix is the discriminator.
        for (candidate in setCandidates) {
            if (!shouldFireSet(candidate.effect, ctx)) continue
            val syntheticId = PerkId(candidate.syntheticPerkId)
            if (!cooldowns.isReady(firer, syntheticId, tick)) continue

            cooldowns.arm(firer, syntheticId, tick + candidate.effect.internalCooldownTicks, tick)
            emitted += CombatEvent.PerkTriggered(
                agent = firer,
                perkId = syntheticId,
                trigger = trigger,
                effectKind = candidate.effect.effectKind,
                params = candidate.effect.params,
                target = (ctx as? TriggerContext.Combat)?.target,
                tick = tick,
                causedBy = causedBy,
            )
        }
        return emitted
    }

    private fun shouldFirePerk(perk: TriggeredPerk, ctx: TriggerContext): Boolean =
        shouldFireSet(perk.effect, ctx)

    private fun shouldFireSet(effect: dev.gvart.genesara.player.PerkEffect.TriggeredPassive, ctx: TriggerContext): Boolean =
        when (effect.trigger) {
            TriggeredPassiveTrigger.ON_LOW_HP -> evaluateHpBandCross(effect, ctx)
            else -> true
        }

    // Descending-edge only: `prev > limit && new <= limit` is naturally idempotent —
    // successive damage while already below the band cannot re-satisfy `prev > limit`.
    private fun evaluateHpBandCross(
        effect: dev.gvart.genesara.player.PerkEffect.TriggeredPassive,
        ctx: TriggerContext,
    ): Boolean {
        if (ctx !is TriggerContext.HpChange) return false
        val thresholdPct = effect.params["thresholdPct"]?.toIntOrNull() ?: return false
        val limit = ctx.maxHp * thresholdPct / 100
        return ctx.prevHp > limit && ctx.newHp <= limit
    }
}
