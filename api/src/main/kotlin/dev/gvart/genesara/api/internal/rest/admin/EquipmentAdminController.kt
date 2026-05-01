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
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.PositiveOrZero
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Admin-only seed path for equipment instances.
 *
 * Lives under `/admin/agents/{agentId}/equipment` so the bearer-token chain in
 * `SecurityConfig` (matching the `/admin` prefix) gates it without extra
 * wiring. Until crafting and loot drops ship, this is the only way to
 * populate the `agent_equipment_instances` table.
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
        @PathVariable agentId: UUID,
        @Valid @RequestBody req: SeedEquipmentRequest,
    ): ResponseEntity<SeedEquipmentResponse> {
        val targetAgent = AgentId(agentId)
        if (agents.find(targetAgent) == null) throw notFound("agent $agentId is not registered")

        val item = items.byId(ItemId(req.itemId))
            ?: throw notFound("item ${req.itemId} is not in the catalog")
        if (item.category != ItemCategory.EQUIPMENT) {
            throw badRequest("item ${req.itemId} is not EQUIPMENT-category")
        }
        val maxDurability = item.maxDurability
            ?: throw badRequest("item ${req.itemId} has no maxDurability — cannot seed an instance")

        // @PositiveOrZero on the DTO already guards the lower bound; only the catalog-derived
        // upper bound needs a domain-level check here.
        val durabilityCurrent = req.durabilityCurrent ?: maxDurability
        if (durabilityCurrent > maxDurability) {
            throw badRequest("durabilityCurrent ($durabilityCurrent) must be in 0..$maxDurability")
        }

        val instance = EquipmentInstance(
            instanceId = UUID.randomUUID(),
            agentId = targetAgent,
            itemId = item.id,
            rarity = req.rarity ?: item.rarity,
            durabilityCurrent = durabilityCurrent,
            durabilityMax = maxDurability,
            creatorAgentId = req.creatorAgentId?.let(::AgentId),
            createdAtTick = tick.currentTick(),
        )
        store.insert(instance)
        return ResponseEntity.status(HttpStatus.CREATED).body(instance.toDto())
    }

    private fun badRequest(detail: String) = ResponseStatusException(HttpStatus.BAD_REQUEST, detail)
    private fun notFound(detail: String) = ResponseStatusException(HttpStatus.NOT_FOUND, detail)
}

data class SeedEquipmentRequest(
    @field:NotBlank val itemId: String,
    val rarity: Rarity? = null,
    @field:PositiveOrZero val durabilityCurrent: Int? = null,
    val creatorAgentId: UUID? = null,
)

data class SeedEquipmentResponse(
    val instanceId: UUID,
    val itemId: String,
    val rarity: Rarity,
    val durabilityCurrent: Int,
    val durabilityMax: Int,
    val creatorAgentId: UUID?,
    val createdAtTick: Long,
)

private fun EquipmentInstance.toDto() = SeedEquipmentResponse(
    instanceId = instanceId,
    itemId = itemId.value,
    rarity = rarity,
    durabilityCurrent = durabilityCurrent,
    durabilityMax = durabilityMax,
    creatorAgentId = creatorAgentId?.id,
    createdAtTick = createdAtTick,
)
