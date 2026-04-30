package dev.gvart.genesara.api.internal.rest.admin

import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.world.EquipmentInstance
import dev.gvart.genesara.world.EquipmentInstanceStore
import dev.gvart.genesara.world.ItemCategory
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.ItemLookup
import dev.gvart.genesara.world.Rarity
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Admin-only seed path for equipment instances.
 *
 * Lives under `/admin/agents/{agentId}/equipment` so the bearer-token chain in
 * `SecurityConfig` (matching the `/admin` prefix) gates it without extra
 * wiring. Until crafting and loot drops ship, this is the only way to
 * populate the `agent_equipment_instances` table — without it the
 * equip / unequip / get_equipment MCP tools have nothing to act on.
 *
 * Validates: agent exists, item is in the catalog and is `EQUIPMENT`-class
 * with a non-null `maxDurability`. Defaults `rarity` to the catalog default
 * and `durabilityCurrent` to the catalog `maxDurability` when the body
 * doesn't override.
 */
@RestController
@RequestMapping("/admin/agents/{agentId}/equipment")
internal class EquipmentAdminController(
    private val items: ItemLookup,
    private val store: EquipmentInstanceStore,
    private val agents: AgentRegistry,
    private val tick: TickClock,
) {

    @PostMapping
    fun seed(
        @PathVariable agentId: String,
        @RequestBody req: SeedEquipmentRequest,
    ): ResponseEntity<SeedEquipmentResponse> {
        val agentUuid = runCatching { UUID.fromString(agentId) }.getOrNull()
            ?: return badRequest("agentId is not a valid UUID: $agentId")
        val targetAgent = AgentId(agentUuid)
        if (agents.find(targetAgent) == null) {
            return notFound("agent $agentId is not registered")
        }

        val itemIdRaw = req.itemId?.takeIf { it.isNotBlank() }
            ?: return badRequest("itemId is required")
        val item = items.byId(ItemId(itemIdRaw))
            ?: return notFound("item $itemIdRaw is not in the catalog")
        if (item.category != ItemCategory.EQUIPMENT) {
            return badRequest("item $itemIdRaw is not EQUIPMENT-category")
        }
        val maxDurability = item.maxDurability
            ?: return badRequest("item $itemIdRaw has no maxDurability — cannot seed an instance")

        val rarity = req.rarity?.takeIf { it.isNotBlank() }?.let { raw ->
            runCatching { Rarity.valueOf(raw.uppercase()) }.getOrNull()
                ?: return badRequest("rarity '$raw' is not a known rarity tier")
        } ?: item.rarity

        val durabilityCurrent = req.durabilityCurrent ?: maxDurability
        if (durabilityCurrent !in 0..maxDurability) {
            return badRequest("durabilityCurrent ($durabilityCurrent) must be in 0..$maxDurability")
        }

        val creator = req.creatorAgentId?.takeIf { it.isNotBlank() }?.let { raw ->
            runCatching { AgentId(UUID.fromString(raw)) }.getOrNull()
                ?: return badRequest("creatorAgentId is not a valid UUID: $raw")
        }

        val instance = EquipmentInstance(
            instanceId = UUID.randomUUID(),
            agentId = targetAgent,
            itemId = item.id,
            rarity = rarity,
            durabilityCurrent = durabilityCurrent,
            durabilityMax = maxDurability,
            creatorAgentId = creator,
            createdAtTick = tick.currentTick(),
        )
        store.insert(instance)
        return ResponseEntity.status(HttpStatus.CREATED).body(instance.toDto())
    }

    private fun badRequest(message: String) =
        ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(SeedEquipmentResponse(error = message))

    private fun notFound(message: String) =
        ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(SeedEquipmentResponse(error = message))
}

data class SeedEquipmentRequest(
    val itemId: String?,
    val rarity: String? = null,
    val durabilityCurrent: Int? = null,
    val creatorAgentId: String? = null,
)

data class SeedEquipmentResponse(
    val instanceId: String? = null,
    val itemId: String? = null,
    val rarity: String? = null,
    val durabilityCurrent: Int? = null,
    val durabilityMax: Int? = null,
    val creatorAgentId: String? = null,
    val createdAtTick: Long? = null,
    val error: String? = null,
)

private fun EquipmentInstance.toDto() = SeedEquipmentResponse(
    instanceId = instanceId.toString(),
    itemId = itemId.value,
    rarity = rarity.name,
    durabilityCurrent = durabilityCurrent,
    durabilityMax = durabilityMax,
    creatorAgentId = creatorAgentId?.id?.toString(),
    createdAtTick = createdAtTick,
)
