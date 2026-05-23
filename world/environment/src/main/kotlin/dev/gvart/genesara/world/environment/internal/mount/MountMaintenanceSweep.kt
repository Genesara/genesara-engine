package dev.gvart.genesara.world.environment.internal.mount

import dev.gvart.genesara.world.MountCatalog
import dev.gvart.genesara.world.MountGauge
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
    private val deathCleanup: MountDeathCleanup,
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
            catalog.byType(mount.type) ?: continue

            val idle = mount.mountedByAgentId == null
            var next = mount.refill(MountGauge.HUNGER, -drain)
            if (idle && next.hunger > hungerLow) {
                next = next.refill(MountGauge.FATIGUE, fatigueRegen)
            }
            if (next.hunger == 0) {
                next = next.refill(MountGauge.HP, -starveDamage)
            } else if (idle && next.hunger >= hungerHigh) {
                next = next.refill(MountGauge.HP, hpRegen)
            }

            if (next.hpCurrent == 0) {
                events += deathCleanup.applyDeath(mount, killer = null, tick = tick, causedBy = null)
                if (!mounts.delete(mount.id)) continue
                // If a rider was on the mount when it starved out, surface
                // TransportDismounted so their client-side state catches up.
                // Starvation typically hits unridden mounts neglected
                // between feedings; but if a logged-in rider lets their
                // mount hit zero hunger mid-ride, the dismount event
                // lets them notice without polling.
                mount.mountedByAgentId?.let { rider ->
                    events += EnvironmentEvent.TransportDismounted(
                        agent = rider,
                        mount = mount.id,
                        mountType = mount.type,
                        at = mount.nodeId,
                        tick = tick,
                        causedBy = null,
                    )
                }
                events += EnvironmentEvent.MountDied(
                    mount = mount.id,
                    mountType = mount.type,
                    at = mount.nodeId,
                    cause = MountDeathCause.STARVATION,
                    tick = tick,
                    causedBy = null,
                )
            } else if (next != mount) {
                mounts.update(next)
            }
        }
        return events
    }
}
