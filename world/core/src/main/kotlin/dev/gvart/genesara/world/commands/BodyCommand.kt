package dev.gvart.genesara.world.commands

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.ItemId
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import java.util.UUID

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@type")
@JsonSubTypes(
    JsonSubTypes.Type(value = BodyCommand.ConsumeItem::class, name = "consume"),
    JsonSubTypes.Type(value = BodyCommand.Drink::class, name = "drink"),
    JsonSubTypes.Type(value = BodyCommand.RefreshDerivedPools::class, name = "refreshDerivedPools"),
    JsonSubTypes.Type(value = BodyCommand.Pickup::class, name = "pickup"),
    JsonSubTypes.Type(value = BodyCommand.Respawn::class, name = "respawn"),
)
sealed interface BodyCommand : WorldCommand {

    data class ConsumeItem(
        override val agent: AgentId,
        val item: ItemId,
        override val commandId: UUID = UUID.randomUUID(),
    ) : BodyCommand

    data class Drink(
        override val agent: AgentId,
        override val commandId: UUID = UUID.randomUUID(),
    ) : BodyCommand

    /**
     * Push a freshly-derived pool maxima triple onto the body cache after an
     * out-of-tick mutation (e.g. `allocate_points`). The reducer copies the
     * supplied maxima onto `state.bodies[agent]` and clamps current values to
     * the new max so a max-reduction never leaves `current > max`. Currents
     * are NOT auto-restored — per the spec, allocation does not heal.
     */
    data class RefreshDerivedPools(
        override val agent: AgentId,
        val maxHp: Int,
        val maxStamina: Int,
        val maxMana: Int,
        override val commandId: UUID = UUID.randomUUID(),
    ) : BodyCommand

    /**
     * Take a ground item identified by [dropId] off the agent's current node.
     * Atomic with concurrent pickups — only the first agent on the same tick
     * succeeds; subsequent ones get [dev.gvart.genesara.world.WorldRejection.GroundItemNoLongerAvailable].
     * Stackable drops land in the agent's inventory; equipment drops land in
     * the equipment store unequipped (slot null) — the agent must call `equip`
     * separately to slot them.
     */
    data class Pickup(
        override val agent: AgentId,
        val dropId: UUID,
        override val commandId: UUID = UUID.randomUUID(),
    ) : BodyCommand

    /**
     * Materialize a dead agent at their safe node. Validates the body is at
     * `hp == 0` and the agent is not currently in the world (the death sweep
     * removed them from `state.positions`).
     */
    data class Respawn(
        override val agent: AgentId,
        override val commandId: UUID = UUID.randomUUID(),
    ) : BodyCommand
}
