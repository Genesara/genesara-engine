package dev.gvart.genesara.world

/**
 * Per-instance Tier-A fauna spawn. Catalog-static fields (damage, defense,
 * aggression) are looked up via [type] against [NpcCatalog]; this row carries
 * only the mutable per-instance state the tick loop reads/writes.
 *
 * `lastAttackTick` defaults to spawn tick so a freshly-seeded NPC can swing
 * immediately if an agent is in range — gating happens via
 * `(currentTick - lastAttackTick) >= type.attackIntervalTicks`.
 */
data class Npc(
    val id: NpcId,
    val type: NpcType,
    val nodeId: NodeId,
    val spawnNodeId: NodeId,
    val hpCurrent: Int,
    val hpMax: Int,
    val spawnedAtTick: Long,
    val lastAttackTick: Long,
) {
    val isDead: Boolean get() = hpCurrent <= 0

    fun takeDamage(amount: Int): Npc =
        copy(hpCurrent = (hpCurrent - amount).coerceAtLeast(0))

    fun moveTo(newNode: NodeId): Npc = copy(nodeId = newNode)

    fun withAttackTick(tick: Long): Npc = copy(lastAttackTick = tick)
}
