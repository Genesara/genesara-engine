package dev.gvart.genesara.api.internal.mcp.tools.inventory

import com.fasterxml.jackson.annotation.JsonClassDescription

@JsonClassDescription("Return the agent's stackable inventory: a list of (itemId, quantity) entries. Read-only — no command queued.")
class GetInventoryRequest

data class GetInventoryResponse(
    val entries: List<InventoryEntryView>,
)

data class InventoryEntryView(
    val itemId: String,
    val quantity: Int,
)
