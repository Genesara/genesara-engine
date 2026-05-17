package dev.gvart.genesara.world.internal.body

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.WorldRejection
import dev.gvart.genesara.world.commands.WorldCommand
import dev.gvart.genesara.world.events.WorldEvent
import dev.gvart.genesara.world.internal.worldstate.WorldState
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class RefreshDerivedPoolsReducerTest {

    private val agent = AgentId(UUID.randomUUID())

    @Test
    fun `updates the maxima on the body, leaving sub-max currents alone`() {
        val state = stateWith(
            AgentBody(
                hp = 30, maxHp = 60,
                stamina = 25, maxStamina = 40,
                mana = 2, maxMana = 5,
            ),
        )
        val command = WorldCommand.RefreshDerivedPools(agent, maxHp = 100, maxStamina = 60, maxMana = 15)

        val (next, _, events) = reduceRefreshDerivedPools(state.body, command, tick = 7).getOrNull()!!

        val body = next.bodyOf(agent)!!
        assertEquals(100, body.maxHp)
        assertEquals(60, body.maxStamina)
        assertEquals(15, body.maxMana)
        // Currents below the previous max stay where they were — allocation does not heal.
        assertEquals(30, body.hp)
        assertEquals(25, body.stamina)
        assertEquals(2, body.mana)
        val event = assertNotNull(events.single() as? WorldEvent.DerivedPoolsRefreshed)
        assertEquals(agent, event.agent)
        assertEquals(100, event.maxHp)
        assertEquals(7L, event.tick)
        assertEquals(command.commandId, event.causedBy)
    }

    @Test
    fun `clamps currents down when the new maxima are lower than the previous currents`() {
        val state = stateWith(
            AgentBody(
                hp = 80, maxHp = 100,
                stamina = 50, maxStamina = 60,
                mana = 12, maxMana = 15,
            ),
        )
        val command = WorldCommand.RefreshDerivedPools(agent, maxHp = 50, maxStamina = 40, maxMana = 5)

        val (next, _, _) = reduceRefreshDerivedPools(state.body, command, tick = 1).getOrNull()!!

        val body = next.bodyOf(agent)!!
        assertEquals(50, body.hp)
        assertEquals(40, body.stamina)
        assertEquals(5, body.mana)
    }

    @Test
    fun `rejects with NotInWorld when the agent has no body row`() {
        val state = WorldState.EMPTY
        val command = WorldCommand.RefreshDerivedPools(agent, maxHp = 100, maxStamina = 60, maxMana = 15)

        val rejection = reduceRefreshDerivedPools(state.body, command, tick = 1).leftOrNull()

        assertEquals(WorldRejection.NotInWorld(agent), rejection)
    }

    private fun stateWith(body: AgentBody): WorldState = WorldState.EMPTY.copy(
        bodies = mapOf(agent to body),
    )
}
