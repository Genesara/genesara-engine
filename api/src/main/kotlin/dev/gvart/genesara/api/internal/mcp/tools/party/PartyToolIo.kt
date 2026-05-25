package dev.gvart.genesara.api.internal.mcp.tools.party

import com.fasterxml.jackson.annotation.JsonInclude
import java.util.UUID

/**
 * Sync-read shape returned by `get_party`. Null `party` means the agent is not
 * in any party; otherwise [PartyView.members] is ordered by join time ascending
 * with the leader first.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class GetPartyResponse(
    val party: PartyView? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class PartyView(
    val partyId: UUID,
    val leader: UUID,
    val formedAtTick: Long,
    val members: List<PartyMemberView>,
)

data class PartyMemberView(
    val agentId: UUID,
    val joinedAtTick: Long,
)
