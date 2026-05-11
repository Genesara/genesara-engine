package dev.gvart.genesara.world.internal.body

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensureNotNull
import dev.gvart.genesara.world.WorldRejection
import dev.gvart.genesara.world.commands.WorldCommand
import dev.gvart.genesara.world.events.WorldEvent
import dev.gvart.genesara.world.internal.worldstate.WorldState

internal fun reduceRefreshDerivedPools(
    state: WorldState,
    command: WorldCommand.RefreshDerivedPools,
    tick: Long,
): Either<WorldRejection, Pair<WorldState, List<WorldEvent>>> = either {
    val body = ensureNotNull(state.bodyOf(command.agent)) {
        WorldRejection.NotInWorld(command.agent)
    }
    val updated = body.copy(
        hp = body.hp.coerceAtMost(command.maxHp),
        maxHp = command.maxHp,
        stamina = body.stamina.coerceAtMost(command.maxStamina),
        maxStamina = command.maxStamina,
        mana = body.mana.coerceAtMost(command.maxMana),
        maxMana = command.maxMana,
    )
    val next = state.updateBody(command.agent, updated)
    val event = WorldEvent.DerivedPoolsRefreshed(
        agent = command.agent,
        maxHp = command.maxHp,
        maxStamina = command.maxStamina,
        maxMana = command.maxMana,
        tick = tick,
        causedBy = command.commandId,
    )
    next to listOf(event)
}
