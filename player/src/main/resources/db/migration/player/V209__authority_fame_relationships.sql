-- Phase 2 reputation slice (#14): per-pair relationship score + global authority/fame.
-- Spec: mechanics-reference §11 (witness cascade) + §19 (Authority & Fame).
ALTER TABLE agents
    ADD COLUMN authority INT NOT NULL DEFAULT 0,
    ADD COLUMN fame      INT NOT NULL DEFAULT 0;

-- Canonical ordering (agent_a < agent_b) keeps one row per pair so adjust
-- writes serialize on a single PK lock; the gateway normalizes argument
-- order before reading or writing.
CREATE TABLE agent_relationships
(
    agent_a               UUID   NOT NULL,
    agent_b               UUID   NOT NULL,
    score                 INT    NOT NULL DEFAULT 0,
    last_changed_at_tick  BIGINT NOT NULL,
    PRIMARY KEY (agent_a, agent_b),
    CONSTRAINT agent_relationships_canonical_order_check CHECK (agent_a < agent_b),
    CONSTRAINT agent_relationships_score_range_check     CHECK (score BETWEEN -100 AND 100)
);

-- Supports the per-agent read path: scoresFor(me) queries
-- `WHERE agent_a = me OR agent_b = me`; without this index the agent_b branch
-- would fall back to a table scan.
CREATE INDEX idx_agent_relationships_agent_b ON agent_relationships (agent_b);
