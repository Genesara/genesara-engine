package dev.gvart.genesara.world

import dev.gvart.genesara.player.AgentId

/**
 * A tamed mount. Per-instance live state — type-static stats live on
 * [MountDef] in the catalog. Permadeath: a dead mount's row is deleted, not
 * flagged.
 *
 * Ownership is recorded in [ownerAgentId]; a null value means the mount was
 * released (abandoned) and is claimable by any agent under their mount cap.
 *
 * Riding is recorded in [mountedByAgentId]; a null value means the mount is
 * idle (and so its fatigue regenerates on the maintenance sweep). Open
 * riding: any agent same-node with an idle mount may mount it, owner or
 * not — owner-only gates apply to gear/cargo/release, not to riding.
 */
data class Mount(
    val id: MountId,
    val type: MountType,
    val ownerAgentId: AgentId?,
    val nodeId: NodeId,
    val hpCurrent: Int,
    val hpMax: Int,
    val hunger: Int,
    val hungerMax: Int,
    val fatigue: Int,
    val fatigueMax: Int,
    val mountedByAgentId: AgentId?,
    val tamedAtTick: Long,
) {
    init {
        require(hpMax > 0) { "hpMax ($hpMax) must be positive" }
        require(hpCurrent in 0..hpMax) { "hpCurrent ($hpCurrent) must be in 0..$hpMax" }
        require(hungerMax > 0) { "hungerMax ($hungerMax) must be positive" }
        require(hunger in 0..hungerMax) { "hunger ($hunger) must be in 0..$hungerMax" }
        require(fatigueMax > 0) { "fatigueMax ($fatigueMax) must be positive" }
        require(fatigue in 0..fatigueMax) { "fatigue ($fatigue) must be in 0..$fatigueMax" }
    }

    val isDead: Boolean get() = hpCurrent <= 0
    val isIdle: Boolean get() = mountedByAgentId == null
    val isOwned: Boolean get() = ownerAgentId != null

    fun takeDamage(amount: Int): Mount =
        copy(hpCurrent = (hpCurrent - amount).coerceAtLeast(0))

    fun moveTo(node: NodeId): Mount = copy(nodeId = node)

    fun mountedBy(agent: AgentId): Mount = copy(mountedByAgentId = agent)

    fun dismounted(): Mount = copy(mountedByAgentId = null)

    fun withOwner(agent: AgentId?): Mount = copy(ownerAgentId = agent)
}
