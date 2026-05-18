package dev.gvart.genesara.api.internal.mcp.tools.relationships

data class GetRelationshipsResponse(
    val authority: Int,
    val fame: Int,
    val entries: List<RelationshipEntryView>,
)

data class RelationshipEntryView(
    val agentId: String,
    /** Display name as it stands on the agent record — null when the other agent's row has since been deleted. */
    val agentName: String?,
    val score: Int,
    val lastChangedAtTick: Long,
)
