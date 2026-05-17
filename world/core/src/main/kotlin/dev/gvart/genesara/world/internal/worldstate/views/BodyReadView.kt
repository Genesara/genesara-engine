package dev.gvart.genesara.world.internal.worldstate.views

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.internal.body.AgentBody
import dev.gvart.genesara.world.internal.inventory.AgentInventory

/**
 * Typed read access to the body slice (per-agent hp/stamina/mana, hunger/thirst,
 * carried inventory).
 *
 * Consumed by zones that need to *check* an agent's body or inventory without
 * being allowed to *mutate* it — combat sees current hp before emitting a
 * `DamageBody` effect; economy checks stamina before charging a craft.
 */
interface BodyReadView {
    val bodies: Map<AgentId, AgentBody>
    val inventories: Map<AgentId, AgentInventory>

    fun bodyOf(agent: AgentId): AgentBody? = bodies[agent]
    fun inventoryOf(agent: AgentId): AgentInventory = inventories[agent] ?: AgentInventory.EMPTY
}
