package dev.gvart.genesara.world.environment.internal.mount

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import dev.gvart.genesara.world.ItemLookup
import dev.gvart.genesara.world.MountGauge
import dev.gvart.genesara.world.MountInstanceStore
import dev.gvart.genesara.world.WorldRejection
import dev.gvart.genesara.world.commands.EnvironmentCommand
import dev.gvart.genesara.world.events.EnvironmentEvent
import dev.gvart.genesara.world.events.WorldEvent
import dev.gvart.genesara.world.internal.worldstate.CrossZoneEffect
import dev.gvart.genesara.world.internal.worldstate.ReducerOutput
import dev.gvart.genesara.world.internal.worldstate.slices.BodySlice
import dev.gvart.genesara.world.internal.worldstate.slices.EnvironmentSlice
import dev.gvart.genesara.world.internal.worldstate.views.CoreReadView

/**
 * `maintain(target, resource, quantity)` — apply maintenance resources to a
 * same-node target. v1 resolves `mount:<uuid>` targets; future iterations add
 * `item:<uuid>` (equipment repair) and `building:<uuid>` (structure repair)
 * through the same verb. Tag-match: resource's `Item.maintenance.type` must
 * equal the target's accepted maintenance type (e.g. `ANIMAL` for mounts).
 * On match, `value × quantity` is restored to the target's maintenance gauge
 * (hunger for ANIMAL mounts).
 *
 * No same-node owner gate — there is no per-agent ownership of mounts;
 * anyone with the resource can feed any nearby mount.
 */
fun reduceMaintain(
    environment: EnvironmentSlice,
    body: BodySlice,
    core: CoreReadView,
    command: EnvironmentCommand.Maintain,
    items: ItemLookup,
    mountCatalog: dev.gvart.genesara.world.MountCatalog,
    mounts: MountInstanceStore,
    tick: Long,
): Either<WorldRejection, ReducerOutput<EnvironmentSlice>> = either {
    ensure(command.quantity > 0) { WorldRejection.NonPositiveQuantity(command.agent, command.quantity) }

    val agentAt = ensureNotNull(core.positions[command.agent]) {
        WorldRejection.NotInWorld(command.agent)
    }
    val mount = ensureNotNull(mounts.findById(command.target)) {
        WorldRejection.UnknownMount(command.agent, command.target)
    }
    ensure(!mount.isDead) { WorldRejection.MountAlreadyDead(command.agent, command.target) }
    ensure(mount.nodeId == agentAt) {
        WorldRejection.MountNotAtSameNode(command.agent, command.target, agentAt, mount.nodeId)
    }

    val itemDef = ensureNotNull(items.byId(command.resource)) {
        WorldRejection.UnknownItem(command.resource)
    }
    val maintenance = ensureNotNull(itemDef.maintenance) {
        WorldRejection.IncompatibleMaintenanceResource(command.agent, command.target.value, command.resource)
    }
    val mountDef = mountCatalog.byType(mount.type)
        ?: error("Catalog corruption: mount ${command.target} has type ${mount.type.value} which has no MountDef")
    ensure(maintenance.type == mountDef.maintenanceType) {
        WorldRejection.IncompatibleMaintenanceResource(command.agent, command.target.value, command.resource)
    }

    val inventory = body.inventoryOf(command.agent)
    ensure(inventory.quantityOf(command.resource) >= command.quantity) {
        WorldRejection.ItemNotInInventory(command.agent, command.resource)
    }

    val restored = (maintenance.value * command.quantity).coerceAtLeast(0)
    val nextMount = mount.refill(MountGauge.HUNGER, restored)
    val actuallyRestored = nextMount.hunger - mount.hunger
    ensure(mounts.update(nextMount)) {
        WorldRejection.MountAlreadyDead(command.agent, command.target)
    }

    val nextInventory = inventory.remove(command.resource, command.quantity)

    val effects = listOf<CrossZoneEffect>(
        CrossZoneEffect.UpdateInventory(command.agent, nextInventory),
    )
    val events: List<WorldEvent> = listOf(
        EnvironmentEvent.Maintained(
            agent = command.agent,
            target = mount.id,
            resource = command.resource,
            quantity = command.quantity,
            restored = actuallyRestored,
            tick = tick,
            causedBy = command.commandId,
        ),
    )
    ReducerOutput(sliceDelta = environment, effects = effects, events = events)
}
