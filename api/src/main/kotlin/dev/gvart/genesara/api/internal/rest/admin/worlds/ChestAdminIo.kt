package dev.gvart.genesara.api.internal.rest.admin.worlds

import java.util.UUID

data class ChestContentsDto(
    val instanceId: UUID,
    val nodeId: Long,
    val contents: Map<String, Int>,
)

data class ReplaceChestContentsRequest(
    val contents: Map<String, Int>,
)

data class PatchChestContentsRequest(
    val itemId: String,
    val delta: Int,
)
