-- Kill streaks moved to Redis (`agent:{id}:streak` hash) per shard-readiness Step 2
-- (issue #79). Streaks are short-lived, loss-tolerant volatile state per Q4 in
-- docs/shard-readiness-sequence.md, so the durable Postgres mirror is dropped
-- rather than left empty.
DROP TABLE IF EXISTS agent_kill_streaks;
