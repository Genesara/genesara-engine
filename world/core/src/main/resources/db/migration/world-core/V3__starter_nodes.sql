-- race_id refers to player-definition/races.yaml; soft cross-module reference (no FK).
-- The table is created empty in this PR; seeding lives in a follow-up admin endpoint
-- because nodes do not exist at migration time. Spawn falls back to randomSpawnableNode()
-- while this table is empty.
CREATE TABLE starter_nodes
(
    race_id VARCHAR(32) PRIMARY KEY,
    node_id BIGINT      NOT NULL REFERENCES nodes (id) ON DELETE CASCADE
);
