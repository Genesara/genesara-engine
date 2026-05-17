package dev.gvart.genesara.api.internal.mcp.tools.relationships

data class GetRelationshipsResponse(
    val authority: Int,
    val fame: Int,
    val entries: List<RelationshipEntryView>,
)

data class RelationshipEntryView(
    val agentId: String,
    val score: Int,
    val lastChangedAtTick: Long,
)
