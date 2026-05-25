package dev.gvart.genesara.world.internal.party

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.internal.balance.BalanceLookup
import dev.gvart.genesara.world.internal.worldstate.views.CoreReadView
import dev.gvart.genesara.world.internal.worldstate.views.PartyReadView

/**
 * Multiplier applied to outgoing damage when [attacker] is in a party AND has
 * at least one other party-mate spawned in the same node as the attacker.
 * Trigger threshold is "≥2 spawned members co-located" — the attacker plus
 * one other. Returns `1.0` when no buff applies (solo attackers, party-mates
 * elsewhere) so callers can multiply unconditionally without branching.
 *
 * Buff magnitude is sourced from [BalanceLookup.partyFormationDamageBonusPercent]
 * so balance tuning lives in one place.
 */
fun formationDamageMultiplier(
    attacker: AgentId,
    attackerNode: NodeId,
    partyReadView: PartyReadView,
    coreView: CoreReadView,
    balance: BalanceLookup,
): Double {
    // Hot path on every attack: short-circuit via the cheap STRING GET before
    // loading the full party roster. Solo agents are the common case and pay
    // only a single Redis round-trip.
    partyReadView.partyIdOf(attacker) ?: return 1.0
    val party = partyReadView.partyOf(attacker) ?: return 1.0
    if (party.size <= 1) return 1.0
    val coLocated = party.members.count { member ->
        coreView.positions[member.agentId] == attackerNode
    }
    if (coLocated < 2) return 1.0
    return 1.0 + balance.partyFormationDamageBonusPercent() / 100.0
}
