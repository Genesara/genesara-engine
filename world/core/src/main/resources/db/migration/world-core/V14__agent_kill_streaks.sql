-- Phase 1 death-system slice (kill-streak item drop): per-agent rolling kill
-- counter. Read by the death sweep when computing drop chance, written by the
-- combat reducer (Phase 2) on every successful kill.
--
-- Rolling-window semantics: when a kill arrives outside the configured window
-- (`BalanceLookup.killStreakWindowTicks`), the counter resets to 1 and
-- window_start_tick advances to the kill's tick. The death sweep treats a
-- counter whose window has expired as zero — `AgentKillStreak.effectiveKillCount`
-- enforces the same rule on read.
--
-- One row per agent (PK on agent_id) — counters overwrite, so an agent always
-- has at most one active streak. Resets on death (the death sweep zeroes the
-- streak so the bonus does not persist across deaths).
--
-- agent_id has no cross-module FK to player.agents (matches the pattern in
-- agent_positions / agent_bodies / agent_safe_nodes / agent_equipment_instances).
-- TODO(phase-5): wire explicit cleanup on permadeath when that lands.
CREATE TABLE agent_kill_streaks
(
    agent_id          UUID    NOT NULL,
    kill_count        INT     NOT NULL CHECK (kill_count >= 0),
    window_start_tick BIGINT  NOT NULL,
    PRIMARY KEY (agent_id)
);
