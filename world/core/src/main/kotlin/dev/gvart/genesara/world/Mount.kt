package dev.gvart.genesara.world

import dev.gvart.genesara.player.AgentId

/**
 * The mount's three persistent gauges. Mirrors the [Gauge]/[dev.gvart.genesara.world.internal.body.AgentBody]
 * shape: high = healthy, depletes per maintenance sweep, refill paths go through
 * [Mount.refill]. HP doubles as the gauge whose zero means permadeath.
 */
enum class MountGauge { HP, HUNGER, FATIGUE }

/**
 * A tamed mount. World-owned creature — there is no per-agent ownership.
 * Anyone same-node may mount, feed, equip gear on, store cargo in, or
 * attack the mount. Permadeath: a dead mount's row is deleted.
 *
 * Riding is recorded in [mountedByAgentId]; a null value means the mount is
 * idle (and so its fatigue regenerates on the maintenance sweep).
 *
 * **Denormalized hot reads** ([saddleSpeedBonus], [harnessCargoBonusGrams],
 * [currentLoadGrams]) cache running aggregates that would otherwise require
 * extra store calls on every movement step / cargo-touch. They are
 * maintained by `EquipMountGearService` (saddle/harness on equip/unequip)
 * and `MountCargoService` (current_load on store/take/stash/withdraw).
 */
data class Mount(
    val id: MountId,
    val type: MountType,
    val nodeId: NodeId,
    val hpCurrent: Int,
    val hpMax: Int,
    val hunger: Int,
    val hungerMax: Int,
    val fatigue: Int,
    val fatigueMax: Int,
    val mountedByAgentId: AgentId?,
    val tamedAtTick: Long,
    val saddleSpeedBonus: Int = 0,
    val harnessCargoBonusGrams: Int = 0,
    val currentLoadGrams: Long = 0L,
) {
    init {
        require(hpMax > 0) { "hpMax ($hpMax) must be positive" }
        require(hpCurrent in 0..hpMax) { "hpCurrent ($hpCurrent) must be in 0..$hpMax" }
        require(hungerMax > 0) { "hungerMax ($hungerMax) must be positive" }
        require(hunger in 0..hungerMax) { "hunger ($hunger) must be in 0..$hungerMax" }
        require(fatigueMax > 0) { "fatigueMax ($fatigueMax) must be positive" }
        require(fatigue in 0..fatigueMax) { "fatigue ($fatigue) must be in 0..$fatigueMax" }
        require(saddleSpeedBonus >= 0) { "saddleSpeedBonus ($saddleSpeedBonus) must be non-negative" }
        require(harnessCargoBonusGrams >= 0) { "harnessCargoBonusGrams ($harnessCargoBonusGrams) must be non-negative" }
        require(currentLoadGrams >= 0L) { "currentLoadGrams ($currentLoadGrams) must be non-negative" }
    }

    val isDead: Boolean get() = hpCurrent <= 0
    val isIdle: Boolean get() = mountedByAgentId == null

    fun takeDamage(amount: Int): Mount =
        copy(hpCurrent = (hpCurrent - amount).coerceAtLeast(0))

    fun moveTo(node: NodeId): Mount = copy(nodeId = node)

    fun mountedBy(agent: AgentId): Mount = copy(mountedByAgentId = agent)

    fun dismounted(): Mount = copy(mountedByAgentId = null)

    /**
     * Refill [gauge] by [amount]; clamped to its max. Negative [amount] drains
     * (used by the maintenance sweep for hunger drain + starvation HP loss).
     * Mirrors `AgentBody.refill`.
     */
    fun refill(gauge: MountGauge, amount: Int): Mount = when (gauge) {
        MountGauge.HP -> copy(hpCurrent = (hpCurrent + amount).coerceIn(0, hpMax))
        MountGauge.HUNGER -> copy(hunger = (hunger + amount).coerceIn(0, hungerMax))
        MountGauge.FATIGUE -> copy(fatigue = (fatigue + amount).coerceIn(0, fatigueMax))
    }

    fun valueOf(gauge: MountGauge): Int = when (gauge) {
        MountGauge.HP -> hpCurrent
        MountGauge.HUNGER -> hunger
        MountGauge.FATIGUE -> fatigue
    }

    fun maxOf(gauge: MountGauge): Int = when (gauge) {
        MountGauge.HP -> hpMax
        MountGauge.HUNGER -> hunger
        MountGauge.FATIGUE -> fatigue
    }
}
