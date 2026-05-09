package dev.gvart.genesara.world.internal.perks

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.PerkCooldownStore
import dev.gvart.genesara.player.TriggeredPassiveLookup
import dev.gvart.genesara.player.TriggeredPassiveTrigger
import dev.gvart.genesara.player.TriggeredPerk
import dev.gvart.genesara.world.events.WorldEvent
import org.springframework.stereotype.Component
import java.util.UUID

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
    private val cooldowns: PerkCooldownStore,
) : TriggeredPassiveDispatcher {

    override fun dispatch(
        firer: AgentId,
        trigger: TriggeredPassiveTrigger,
        ctx: TriggerContext,
        tick: Long,
        causedBy: UUID?,
    ): List<WorldEvent> {
        val candidates = lookup.matching(firer, trigger)
        if (candidates.isEmpty()) return emptyList()

        val emitted = mutableListOf<WorldEvent>()
        for (candidate in candidates) {
            if (!shouldFire(candidate, ctx)) continue
            if (!cooldowns.isReady(firer, candidate.perk.id, tick)) continue

            cooldowns.arm(firer, candidate.perk.id, tick + candidate.effect.internalCooldownTicks, tick)
            emitted += WorldEvent.PerkTriggered(
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
        return emitted
    }

    private fun shouldFire(perk: TriggeredPerk, ctx: TriggerContext): Boolean = when (perk.effect.trigger) {
        TriggeredPassiveTrigger.ON_LOW_HP -> evaluateHpBandCross(perk, ctx)
        else -> true
    }

    // Descending-edge only: `prev > limit && new <= limit` is naturally idempotent —
    // successive damage while already below the band cannot re-satisfy `prev > limit`.
    private fun evaluateHpBandCross(perk: TriggeredPerk, ctx: TriggerContext): Boolean {
        if (ctx !is TriggerContext.HpChange) return false
        val thresholdPct = perk.effect.params["thresholdPct"]?.toIntOrNull() ?: return false
        val limit = ctx.maxHp * thresholdPct / 100
        return ctx.prevHp > limit && ctx.newHp <= limit
    }
}
