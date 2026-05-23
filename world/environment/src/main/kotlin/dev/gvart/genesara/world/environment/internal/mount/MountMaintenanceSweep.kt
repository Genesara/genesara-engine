package dev.gvart.genesara.world.environment.internal.mount

import dev.gvart.genesara.world.MountCatalog
import dev.gvart.genesara.world.MountInstanceStore
import dev.gvart.genesara.world.events.EnvironmentEvent
import dev.gvart.genesara.world.events.MountDeathCause
import dev.gvart.genesara.world.events.WorldEvent
import dev.gvart.genesara.world.internal.balance.BalanceLookup
import org.springframework.stereotype.Component

/**
 * Periodic mount-maintenance pass run inside the tick loop. One DB sweep per
 * `mountMaintenancePeriodTicks`:
 *   - hunger drain (every cycle)
 *   - fatigue regen if mount is idle (`mountedByAgentId == null`)
 *   - HP regen if mount is fed (hunger ≥ buff threshold) AND idle
 *   - starvation HP loss when hunger has hit zero
 *   - on HP → 0: emit `MountDied(cause = STARVATION)`, delete the row
 *
 * Gear-and-cargo cascade on death is the responsibility of the side-effect
 * applier that consumes `MountDied` events (TODO: wire that consumer in
 * stage-G follow-up so cargo drops + gear deletes ride event-driven). For
 * now the sweep just deletes the mount row; orphaned mount_inventory rows
 * are auto-deleted by the V27 ON DELETE CASCADE on `mount_inventory.mount_id`.
 */
@Component
class MountMaintenanceSweep(
    private val mounts: MountInstanceStore,
    private val catalog: MountCatalog,
    private val balance: BalanceLookup,
) {

    fun sweep(tick: Long): List<WorldEvent> {
        if (tick % balance.mountMaintenancePeriodTicks().coerceAtLeast(1) != 0L) {
            return emptyList()
        }
        val events = mutableListOf<WorldEvent>()
        val drain = balance.mountHungerDrainPerPeriod()
        val fatigueRegen = balance.mountFatigueRegenPerPeriod()
        val hpRegen = balance.mountHpRegenPerPeriod()
        val starveDamage = balance.mountStarvationDamagePerPeriod()
        val hungerLow = balance.mountHungerLowThreshold()
        val hungerHigh = balance.mountHungerBuffThreshold()

        for (mount in mounts.all()) {
            if (mount.isDead) continue
            val def = catalog.byType(mount.type) ?: continue

            var hunger = (mount.hunger - drain).coerceAtLeast(0)
            val idle = mount.mountedByAgentId == null
            var fatigue = if (idle && hunger > hungerLow) {
                (mount.fatigue + fatigueRegen).coerceAtMost(mount.fatigueMax)
            } else {
                mount.fatigue
            }
            var hp = mount.hpCurrent
            if (hunger == 0) {
                hp = (hp - starveDamage).coerceAtLeast(0)
            } else if (idle && hunger >= hungerHigh) {
                hp = (hp + hpRegen).coerceAtMost(mount.hpMax)
            }

            if (hp == 0) {
                mounts.delete(mount.id)
                events += EnvironmentEvent.MountDied(
                    mount = mount.id,
                    mountType = mount.type,
                    owner = mount.ownerAgentId,
                    at = mount.nodeId,
                    cause = MountDeathCause.STARVATION,
                    tick = tick,
                    causedBy = null,
                )
            } else if (hp != mount.hpCurrent || hunger != mount.hunger || fatigue != mount.fatigue) {
                mounts.update(mount.copy(hpCurrent = hp, hunger = hunger, fatigue = fatigue))
            }
        }
        return events
    }
}
