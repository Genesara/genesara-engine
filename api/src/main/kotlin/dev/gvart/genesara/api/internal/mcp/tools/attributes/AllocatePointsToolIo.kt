package dev.gvart.genesara.api.internal.mcp.tools.attributes

import dev.gvart.genesara.player.AgentAttributes
import dev.gvart.genesara.player.Attribute
import dev.gvart.genesara.player.AttributeMilestoneCrossing

enum class AllocatePointsKind { OK, REJECTED }

enum class AllocatePointsRejectionReason {
    NEGATIVE_DELTA,
    INSUFFICIENT_POINTS,
    AGENT_MISSING,
}

data class AllocatePointsResponse(
    val kind: AllocatePointsKind,
    val reason: AllocatePointsRejectionReason? = null,
    val detail: String? = null,
    val attributes: Map<Attribute, Int>? = null,
    val remainingUnspent: Int? = null,
    val crossedMilestones: List<MilestoneEntry>? = null,
) {
    data class MilestoneEntry(val attribute: Attribute, val milestone: Int)

    companion object {
        fun ok(
            attrs: AgentAttributes,
            remainingUnspent: Int,
            crossings: List<AttributeMilestoneCrossing>,
        ): AllocatePointsResponse = AllocatePointsResponse(
            kind = AllocatePointsKind.OK,
            attributes = Attribute.entries.associateWith { it.valueOn(attrs) },
            remainingUnspent = remainingUnspent,
            crossedMilestones = crossings.map { MilestoneEntry(it.attribute, it.milestone) },
        )

        fun rejected(reason: AllocatePointsRejectionReason, detail: String): AllocatePointsResponse =
            AllocatePointsResponse(kind = AllocatePointsKind.REJECTED, reason = reason, detail = detail)
    }
}
