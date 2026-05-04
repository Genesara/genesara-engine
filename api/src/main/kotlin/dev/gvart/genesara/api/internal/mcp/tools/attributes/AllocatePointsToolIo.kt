package dev.gvart.genesara.api.internal.mcp.tools.attributes

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import dev.gvart.genesara.player.AgentAttributes
import dev.gvart.genesara.player.Attribute
import dev.gvart.genesara.player.AttributeMilestoneCrossing

@JsonClassDescription(
    "Spend unspent attribute points to permanently raise attributes. THIS IS IRREVERSIBLE — " +
        "there is no respec or unallocate. Each attribute increase is locked in. " +
        "Verify the agent's unspent pool with `get_status` before calling.",
)
data class AllocatePointsRequest(
    @JsonPropertyDescription(
        "Map of attribute (STRENGTH, DEXTERITY, CONSTITUTION, PERCEPTION, INTELLIGENCE, LUCK) " +
            "to non-negative points to add. Sum must be <= the agent's current " +
            "unspentAttributePoints.",
    )
    val deltas: Map<Attribute, Int>,
)

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
