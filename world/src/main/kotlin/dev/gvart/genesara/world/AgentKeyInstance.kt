package dev.gvart.genesara.world

import dev.gvart.genesara.player.AgentId
import java.util.UUID

/**
 * A single physical key in an agent's inventory. Mirrors [EquipmentInstance]'s
 * shape: per-instance UUID PK, no stack semantics, soft refs to the holding
 * agent and the gate it opens. Backed by `agent_keys`.
 *
 * Keys cannot merge because each instance encodes the gate it targets — two
 * keys to two different gates can never be a stack of two, even when the
 * underlying [ItemId] (GATE_KEY) is the same.
 */
data class AgentKeyInstance(
    val instanceId: UUID,
    val agentId: AgentId,
    val itemId: ItemId,
    val gateInstanceId: UUID,
    val createdAtTick: Long,
)
