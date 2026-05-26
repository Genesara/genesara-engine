package dev.gvart.genesara.world

interface AdminNpcGateway {
    fun spawn(nodeId: NodeId, type: NpcType, hp: Int?, tick: Long): Npc

    fun listAtNode(nodeId: NodeId): List<Npc>

    fun update(npcId: NpcId, hp: Int?, nodeId: NodeId?, tick: Long): Npc

    fun kill(npcId: NpcId, silent: Boolean, tick: Long): KillOutcome
}

data class KillOutcome(
    val npc: Npc,
    val drops: List<DroppedItemView>,
)

sealed class AdminNpcGatewayError(message: String) : RuntimeException(message) {
    class UnknownType(val type: NpcType) :
        AdminNpcGatewayError("Unknown NPC type: ${type.value}")
    class NotFound(val npcId: NpcId) :
        AdminNpcGatewayError("NPC not found: ${npcId.value}")
    class InvalidHp(val requested: Int, val hpMax: Int) :
        AdminNpcGatewayError("hp ($requested) must be in 1..$hpMax")
}
