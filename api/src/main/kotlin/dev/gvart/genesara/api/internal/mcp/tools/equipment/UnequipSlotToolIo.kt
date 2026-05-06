package dev.gvart.genesara.api.internal.mcp.tools.equipment

import dev.gvart.genesara.world.EquipSlot
import java.util.UUID

data class UnequipSlotResponse(
    val kind: String,
    val slot: EquipSlot,
    val instanceId: UUID? = null,
)
