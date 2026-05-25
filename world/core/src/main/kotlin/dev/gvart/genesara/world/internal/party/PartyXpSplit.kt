package dev.gvart.genesara.world.internal.party

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.internal.movement.hopDistance
import dev.gvart.genesara.world.internal.worldstate.views.CoreReadView
import dev.gvart.genesara.world.internal.worldstate.views.PartyReadView

/**
 * Computes the set of party members eligible to share a kill-bonus XP grant
 * with [killer] — every member of [killer]'s party who is currently spawned
 * (present in [CoreReadView.positions]) AND whose node sits within [radius]
 * hops of [killerNode].
 *
 * When [killer] is not in a party (or has no party-mates in range), the
 * result is `[killer]` alone — the same single-member set the pre-party
 * solo-kill path would produce. Callers can therefore route every kill
 * bonus through this list without branching on "in party or not."
 *
 * Always includes [killer], since they are necessarily at [killerNode] and
 * at hop distance 0 from themselves.
 */
fun eligibleKillSplitMembers(
    killer: AgentId,
    killerNode: NodeId,
    radius: Int,
    partyReadView: PartyReadView,
    coreView: CoreReadView,
): List<AgentId> {
    val party = partyReadView.partyOf(killer) ?: return listOf(killer)
    if (party.size <= 1) return listOf(killer)
    val result = mutableListOf<AgentId>()
    for (member in party.members) {
        val memberId = member.agentId
        if (memberId == killer) {
            result += killer
            continue
        }
        val memberNode = coreView.positions[memberId] ?: continue
        val distance = hopDistance(coreView, killerNode, memberNode, radius)
        if (distance in 0..radius) {
            result += memberId
        }
    }
    if (killer !in result) result += killer
    return result
}
