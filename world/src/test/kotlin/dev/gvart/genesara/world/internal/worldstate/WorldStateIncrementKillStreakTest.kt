package dev.gvart.genesara.world.internal.worldstate

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.AgentKillStreak
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals

/**
 * Pins the rolling-window contract for the public-API surface combat (Phase 2)
 * will plug into. The first kill always anchors the window to the kill tick;
 * subsequent kills inside the window add 1; a kill outside the window starts a
 * fresh streak.
 */
class WorldStateIncrementKillStreakTest {

    private val agent = AgentId(UUID.randomUUID())
    private val state = WorldState.EMPTY

    @Test
    fun `first kill at low tick anchors windowStartTick to the kill tick`() {
        // Regression for the EMPTY-sentinel edge case: with a default
        // (killCount=0, windowStartTick=0L) and a low currentTick, the naive
        // "currentTick - 0 < windowTicks" branch would run the within-window
        // path and produce (1, 0L) instead of (1, currentTick). The streak
        // would then expire as soon as currentTick crosses windowTicks rather
        // than 1000 ticks after the first kill.
        val next = state.incrementKillStreak(agent, currentTick = 500L, windowTicks = 1000L)

        assertEquals(
            AgentKillStreak(killCount = 1, windowStartTick = 500L),
            next.killStreakOf(agent),
        )
    }

    @Test
    fun `second kill inside window adds 1 and keeps windowStartTick`() {
        val first = state.incrementKillStreak(agent, currentTick = 500L, windowTicks = 1000L)

        val second = first.incrementKillStreak(agent, currentTick = 999L, windowTicks = 1000L)

        assertEquals(
            AgentKillStreak(killCount = 2, windowStartTick = 500L),
            second.killStreakOf(agent),
        )
    }

    @Test
    fun `kill at the window boundary expires the prior streak and re-anchors`() {
        val first = state.incrementKillStreak(agent, currentTick = 500L, windowTicks = 1000L)

        // currentTick - windowStartTick = 1500 - 500 = 1000 >= windowTicks → expired.
        val second = first.incrementKillStreak(agent, currentTick = 1500L, windowTicks = 1000L)

        assertEquals(
            AgentKillStreak(killCount = 1, windowStartTick = 1500L),
            second.killStreakOf(agent),
        )
    }

    @Test
    fun `kill far outside window resets the streak`() {
        val first = state.incrementKillStreak(agent, currentTick = 100L, windowTicks = 1000L)

        val second = first.incrementKillStreak(agent, currentTick = 5000L, windowTicks = 1000L)

        assertEquals(
            AgentKillStreak(killCount = 1, windowStartTick = 5000L),
            second.killStreakOf(agent),
        )
    }
}
