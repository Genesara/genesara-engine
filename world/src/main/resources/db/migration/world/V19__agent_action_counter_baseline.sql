-- Skill-feature step 8 (issue #34): the L50 evolution scorer needs a "windowed"
-- behavior snapshot — only counters from the recent post-class-choice stretch
-- count, so an agent that pivoted playstyle after L10 can evolve down a new
-- branch.
--
-- Cheapest faithful implementation given the existing cumulative-only counter
-- shape: add a per-row baseline. When `select_class` commits the L10 pick we
-- copy `action_count` → `baseline_count` for every existing row of the agent;
-- categories the agent first touches AFTER classing implicitly start at
-- baseline 0 (default below), so the read `action_count - baseline_count` is
-- exactly the post-classing count without a tick-bucketed history table.
--
-- Concurrency invariant for `baseline <= action_count`:
--   `record()` is the only writer of `action_count`, and it ONLY increments
--   (no decrement, no reset). So any value of `action_count` observed by a
--   `markBaseline` UPDATE is a lower-bound for every subsequent observation
--   in another transaction. Postgres takes a row-level lock on the UPDATE,
--   so the listener's `SET baseline_count = action_count` is atomic with the
--   read of `action_count`. The CHECK is a belt-and-braces guard against
--   future schema edits that break monotonicity (e.g. a "decay" job that
--   shrinks counters); break it loudly rather than letting `snapshotForWindow`
--   return negative numbers downstream.
ALTER TABLE agent_action_counters
    ADD COLUMN baseline_count INT NOT NULL DEFAULT 0
        CHECK (baseline_count >= 0 AND baseline_count <= action_count);
