package dev.gvart.genesara.api.internal.mcp.tools.loadout

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityTracker
import dev.gvart.genesara.api.internal.mcp.presence.touchActivity
import dev.gvart.genesara.api.internal.mcp.tools.PrefixedIds
import dev.gvart.genesara.world.AgentItemInstancesStore
import dev.gvart.genesara.world.EquipSlot
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.ItemInstance
import dev.gvart.genesara.world.ItemLookup
import dev.gvart.genesara.world.Rarity
import dev.gvart.genesara.world.WorldQueryGateway
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.annotation.Tool
import org.springframework.stereotype.Component

@Component
internal class GetLoadoutTool(
    private val world: WorldQueryGateway,
    private val items: ItemLookup,
    private val store: AgentItemInstancesStore,
    private val activity: AgentActivityTracker,
) {

    @Tool(
        name = "get_loadout",
        description = "Return everything the agent is carrying. " +
            "`stackable` is pure stack-shaped inventory (one row per (itemId) with `quantity`). " +
            "`instances` lists per-instance non-equipment items (today: KEY rows carrying " +
            "`gateInstanceId`). The `equipment` block lists every defined slot in stable order " +
            "with the instance currently in it (or null if empty), plus a stash of per-instance " +
            "gear owned but not slotted. Read-only.",
    )
    fun invoke(toolContext: ToolContext): GetLoadoutResponse {
        touchActivity(toolContext, activity, "get_loadout")
        val agentId = AgentContextHolder.current()

        val inventory = world.inventoryOf(agentId)
        // Single fetch — splitting into equippedFor + listByAgent races a concurrent
        // equip and can show the same instance in both `slots` and `stash`.
        val all = store.listByAgent(agentId)
        val equipment = all.filterIsInstance<ItemInstance.Equipment>()
        val keys = all.filterIsInstance<ItemInstance.Key>()

        val (equippedList, stashList) = equipment.partition { it.equippedInSlot != null }
        val bySlot = equippedList.associateBy { it.equippedInSlot!! }

        return GetLoadoutResponse(
            stackable = inventory.entries.map { entry ->
                InventoryEntryView(
                    itemId = entry.itemId.value,
                    quantity = entry.quantity,
                    rarity = rarityFor(entry.itemId),
                )
            },
            instances = keys.map { key ->
                ItemInstanceView(
                    instanceId = key.instanceId.toString(),
                    itemId = key.itemId.value,
                    category = "KEY",
                    rarity = rarityFor(key.itemId),
                    gateInstanceId = key.gateInstanceId.toString(),
                )
            },
            equipment = EquipmentView(
                slots = EquipSlot.entries.map { slot ->
                    EquipmentSlotView(
                        slotId = slot.name,
                        instance = bySlot[slot]?.toView(),
                    )
                },
                stash = stashList.map { it.toView() },
            ),
        )
    }

    /**
     * Falls back to [Rarity.COMMON] when the catalog has no entry for [itemId]:
     * `agent_inventory` references the catalog implicitly but jOOQ doesn't enforce a FK
     * (catalog is YAML-driven), so a stale row should project robustly rather than throw.
     */
    private fun rarityFor(itemId: ItemId): Rarity = items.byId(itemId)?.rarity ?: Rarity.COMMON

    private fun ItemInstance.Equipment.toView(): EquipmentInstanceView = EquipmentInstanceView(
        instanceId = instanceId.toString(),
        itemId = itemId.value,
        rarity = rarity,
        durabilityCurrent = durabilityCurrent,
        durabilityMax = durabilityMax,
        creatorAgentId = creatorAgentId?.let(PrefixedIds::encodeAgent),
    )
}
