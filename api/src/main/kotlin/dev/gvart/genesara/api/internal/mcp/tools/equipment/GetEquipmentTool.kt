package dev.gvart.genesara.api.internal.mcp.tools.equipment

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityTracker
import dev.gvart.genesara.api.internal.mcp.presence.touchActivity
import dev.gvart.genesara.world.EquipmentInstance
import dev.gvart.genesara.world.EquipmentInstanceStore
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.annotation.Tool
import org.springframework.stereotype.Component

@Component
internal class GetEquipmentTool(
    private val store: EquipmentInstanceStore,
    private val activity: AgentActivityTracker,
) {

    @Tool(
        name = "get_equipment",
        description = "Return the agent's full equipment picture: a slot → instance map " +
            "for currently-equipped gear plus the list of unequipped instances in the " +
            "stash. Read-only.",
    )
    fun invoke(req: GetEquipmentRequest, toolContext: ToolContext): GetEquipmentResponse {
        touchActivity(toolContext, activity)
        val agent = AgentContextHolder.current()

        // Single round-trip — partition in memory. A two-query shape (equippedFor
        // + listByAgent) can otherwise return a mismatched view under concurrent
        // equip, where the same instance shows up in both `equipped` and `stash`.
        val (equippedList, stash) = store.listByAgent(agent).partition { it.equippedInSlot != null }

        return GetEquipmentResponse(
            equipped = equippedList.associate { it.equippedInSlot!!.name to it.toView() },
            stash = stash.map { it.toView() },
        )
    }

    private fun EquipmentInstance.toView(): EquipmentInstanceView = EquipmentInstanceView(
        instanceId = instanceId.toString(),
        itemId = itemId.value,
        rarity = rarity.name,
        durabilityCurrent = durabilityCurrent,
        durabilityMax = durabilityMax,
        equippedInSlot = equippedInSlot?.name,
        creatorAgentId = creatorAgentId?.id?.toString(),
    )
}
