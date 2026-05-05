package dev.gvart.genesara.world

/**
 * Per-agent rolling kill counter that drives the death-sweep drop chance.
 * Persisted in `agent_kill_streaks`; loaded into `WorldState.killStreaks`.
 *
 * Window semantics: a kill at tick `t` is "in the window" when
 * `t - windowStartTick < windowTicks`. The combat reducer (Phase 2) calls
 * [WorldState.incrementKillStreak] which encapsulates the reset rule so
 * callers do not branch on the window themselves.
 */
data class AgentKillStreak(
    val killCount: Int,
    val windowStartTick: Long,
) {
    init {
        require(killCount >= 0) { "killCount ($killCount) must be non-negative" }
    }

    /**
     * The kill count the death sweep should treat as live at [currentTick].
     * Returns 0 when the window has expired so the drop-chance formula sees
     * a fresh streak rather than stale credit.
     */
    fun effectiveKillCount(currentTick: Long, windowTicks: Long): Int =
        if (currentTick - windowStartTick < windowTicks) killCount else 0

    companion object {
        val EMPTY: AgentKillStreak = AgentKillStreak(killCount = 0, windowStartTick = 0L)
    }
}
