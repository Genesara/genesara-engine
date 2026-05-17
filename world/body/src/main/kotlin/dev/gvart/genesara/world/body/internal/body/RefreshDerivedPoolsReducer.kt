package dev.gvart.genesara.world.body.internal.body

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensureNotNull
import dev.gvart.genesara.world.WorldRejection
import dev.gvart.genesara.world.commands.BodyCommand
import dev.gvart.genesara.world.events.BodyEvent
import dev.gvart.genesara.world.internal.worldstate.ReducerOutput
import dev.gvart.genesara.world.internal.worldstate.slices.BodySlice

fun reduceRefreshDerivedPools(
    body: BodySlice,
    command: BodyCommand.RefreshDerivedPools,
    tick: Long,
): Either<WorldRejection, ReducerOutput<BodySlice>> = either {
    val current = ensureNotNull(body.bodyOf(command.agent)) {
        WorldRejection.NotInWorld(command.agent)
    }
    val updated = current.copy(
        hp = current.hp.coerceAtMost(command.maxHp),
        maxHp = command.maxHp,
        stamina = current.stamina.coerceAtMost(command.maxStamina),
        maxStamina = command.maxStamina,
        mana = current.mana.coerceAtMost(command.maxMana),
        maxMana = command.maxMana,
    )
    val nextSlice = body.copy(bodies = body.bodies + (command.agent to updated))
    val event = BodyEvent.DerivedPoolsRefreshed(
        agent = command.agent,
        maxHp = command.maxHp,
        maxStamina = command.maxStamina,
        maxMana = command.maxMana,
        tick = tick,
        causedBy = command.commandId,
    )
    ReducerOutput(sliceDelta = nextSlice, events = listOf(event))
}
