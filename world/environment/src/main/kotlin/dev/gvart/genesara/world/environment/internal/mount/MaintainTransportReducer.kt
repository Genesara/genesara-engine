package dev.gvart.genesara.world.environment.internal.mount

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import dev.gvart.genesara.world.ItemLookup
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
 * `maintain_transport(mount, item, qty)` — apply maintenance resources to a
 * mount. Tag-match: the resource's `Item.maintenance.type` must equal the
 * mount's `MountDef.maintenanceType` (v1: ANIMAL). Restored gauge is hunger
 * for ANIMAL mounts; future engine vehicles will route to fuel/wear via the
 * same verb.
 *
 * Not owner-gated: any same-node agent may feed a mount (caregiver model).
 * Consumes from the agent's inventory; rejects on missing tag, type mismatch,
 * or insufficient stock.
 */
fun reduceMaintainTransport(
    environment: EnvironmentSlice,
    body: BodySlice,
    core: CoreReadView,
    command: EnvironmentCommand.MaintainTransport,
    items: ItemLookup,
    mountCatalog: dev.gvart.genesara.world.MountCatalog,
    mounts: MountInstanceStore,
    tick: Long,
): Either<WorldRejection, ReducerOutput<EnvironmentSlice>> = either {
    ensure(command.quantity > 0) { WorldRejection.NonPositiveQuantity(command.agent, command.quantity) }

    val agentAt = ensureNotNull(core.positions[command.agent]) {
        WorldRejection.NotInWorld(command.agent)
    }
    val mount = ensureNotNull(mounts.findById(command.mount)) {
        WorldRejection.UnknownMount(command.agent, command.mount)
    }
    ensure(!mount.isDead) { WorldRejection.MountAlreadyDead(command.agent, command.mount) }
    ensure(mount.nodeId == agentAt) {
        WorldRejection.MountNotAtSameNode(command.agent, command.mount, agentAt, mount.nodeId)
    }

    val itemDef = ensureNotNull(items.byId(command.resource)) {
        WorldRejection.UnknownItem(command.resource)
    }
    val maintenance = ensureNotNull(itemDef.maintenance) {
        WorldRejection.IncompatibleMaintenanceResource(command.agent, command.mount, command.resource)
    }
    val mountDef = mountCatalog.byType(mount.type)
        ?: error("Catalog corruption: mount ${command.mount} has type ${mount.type.value} which has no MountDef")
    ensure(maintenance.type == mountDef.maintenanceType) {
        WorldRejection.IncompatibleMaintenanceResource(command.agent, command.mount, command.resource)
    }

    val inventory = body.inventoryOf(command.agent)
    ensure(inventory.quantityOf(command.resource) >= command.quantity) {
        WorldRejection.ItemNotInInventory(command.agent, command.resource)
    }

    val restored = (maintenance.value * command.quantity).coerceAtLeast(0)
    val newHunger = (mount.hunger + restored).coerceAtMost(mount.hungerMax)
    val actuallyRestored = newHunger - mount.hunger
    mounts.update(mount.copy(hunger = newHunger))

    val nextInventory = inventory.remove(command.resource, command.quantity)

    val effects = listOf<CrossZoneEffect>(
        CrossZoneEffect.UpdateInventory(command.agent, nextInventory),
    )
    val events: List<WorldEvent> = listOf(
        EnvironmentEvent.TransportMaintained(
            agent = command.agent,
            mount = mount.id,
            resource = command.resource,
            quantity = command.quantity,
            restored = actuallyRestored,
            tick = tick,
            causedBy = command.commandId,
        ),
    )
    ReducerOutput(sliceDelta = environment, effects = effects, events = events)
}
