-- Phase 4 behavior tracker (issue #31): per-agent silent counters that drive
-- the level-10 class-choice fingerprint and (later) the L50 evolution scoring.
--
-- TODO(permadeath-cleanup): no cross-module FK to player.agents (matches the
-- agent_safe_nodes pattern); wire explicit cleanup when permadeath lands.
--
-- TODO(window): the sliding-window read for class evolution (#34) may need
-- tick-bucketing on top of this single-row shape — `last_incremented_at_tick`
-- buys us a recency hint without committing to that shape now.
--
-- No CHECK constraint on `category`: a check would force a migration on every
-- new ActionCategory axis; trust the application enum and let unknown strings
-- surface as a snapshot read miss (logged in JooqBehaviorTracker).
--
-- `action_count` rather than `count` keeps the column out of jOOQ's aggregate
-- function namespace and mirrors the `recommend_count` pattern in
-- agent_skill_recommendations.
CREATE TABLE agent_action_counters
(
    agent_id                 UUID        NOT NULL,
    category                 VARCHAR(16) NOT NULL,
    action_count             INT         NOT NULL DEFAULT 0 CHECK (action_count >= 0),
    last_incremented_at_tick BIGINT      NOT NULL,
    PRIMARY KEY (agent_id, category)
);
