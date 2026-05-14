package dev.gvart.genesara.api.internal.mcp.tools.loadout

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityTracker
import dev.gvart.genesara.api.internal.mcp.presence.touchActivity
import dev.gvart.genesara.world.AgentKeysStore
import dev.gvart.genesara.world.EquipSlot
import dev.gvart.genesara.world.EquipmentInstance
import dev.gvart.genesara.world.EquipmentInstanceStore
import dev.gvart.genesara.world.ItemId
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
    private val store: EquipmentInstanceStore,
    private val keys: AgentKeysStore,
    private val activity: AgentActivityTracker,
) {

    @Tool(
        name = "get_loadout",
        description = "Return everything the agent is carrying: stackable inventory entries " +
            "(itemId + quantity + catalog rarity) plus per-instance equipment. Per-instance " +
            "inventory items (e.g. GATE_KEY) appear as stackable entries with quantity=1 and " +
            "carry their `instanceId`; GATE_KEY entries also carry `gateInstanceId` — the " +
            "building instance id of the gate they open. The equipment block lists every " +
            "defined slot in stable order with the instance currently in it (or null if " +
            "empty), plus a stash of per-instance gear owned but not slotted. Read-only.",
    )
    fun invoke(toolContext: ToolContext): GetLoadoutResponse {
        touchActivity(toolContext, activity, "get_loadout")
        val agentId = AgentContextHolder.current()

        val inventory = world.inventoryOf(agentId)
        // Partition a single listByAgent — splitting into equippedFor + listByAgent races
        // a concurrent equip and can show the same instance in both `slots` and `stash`.
        val (equippedList, stashList) = store.listByAgent(agentId).partition { it.equippedInSlot != null }
        val bySlot = equippedList.associateBy { it.equippedInSlot!! }

        val stackableFromInventory = inventory.entries.map { entry ->
            InventoryEntryView(
                itemId = entry.itemId.value,
                quantity = entry.quantity,
                rarity = rarityFor(entry.itemId),
            )
        }
        val stackableFromKeys = keys.listByAgent(agentId).map { key ->
            InventoryEntryView(
                itemId = key.itemId.value,
                quantity = 1,
                rarity = rarityFor(key.itemId),
                instanceId = key.instanceId.toString(),
                gateInstanceId = key.gateInstanceId.toString(),
            )
        }

        return GetLoadoutResponse(
            stackable = stackableFromInventory + stackableFromKeys,
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

    private fun EquipmentInstance.toView(): EquipmentInstanceView = EquipmentInstanceView(
        instanceId = instanceId.toString(),
        itemId = itemId.value,
        rarity = rarity,
        durabilityCurrent = durabilityCurrent,
        durabilityMax = durabilityMax,
        creatorAgentId = creatorAgentId?.id?.toString(),
    )
}
