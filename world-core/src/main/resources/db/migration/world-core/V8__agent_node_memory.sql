-- Phase 0 finishing slice: per-agent map memory.
--
-- Records every node an agent has had in vision (current tile + adjacent tiles
-- inside sight range) so `get_map()` can return the agent's known world without
-- exposing live state. Last-seen-tick tells the agent how stale each entry is —
-- terrain and biome may have changed since they last saw the tile.
--
-- BOTH terrain AND biome are snapshotted (`last_terrain`, `last_biome`) so a stale
-- recall reflects what the agent *saw*, not whatever the tile looks like now after
-- an admin re-paint or future world-shaping event. `last_biome` is nullable
-- because regions may have a null biome (unpainted in early dev).
--
-- ON DELETE CASCADE on node_id: when an admin deletes a node, the memory rows
-- follow rather than orphan. The agent's recall just loses that entry; this is
-- preferable to surfacing stale ids that no longer resolve.
--
-- agent_id intentionally has no FK to player.agents — cross-module FKs are
-- avoided in this schema (matches the agent_positions / agent_bodies pattern).
-- TODO(phase-5): wire an explicit cleanup when permadeath / hard agent deletion
-- ships, otherwise rows leak.
CREATE TABLE agent_node_memory
(
    agent_id        UUID         NOT NULL,
    node_id         BIGINT       NOT NULL REFERENCES nodes (id) ON DELETE CASCADE,
    first_seen_tick BIGINT       NOT NULL,
    last_seen_tick  BIGINT       NOT NULL,
    last_terrain    VARCHAR(32)  NOT NULL,
    last_biome      VARCHAR(32),
    PRIMARY KEY (agent_id, node_id)
);

CREATE INDEX idx_agent_node_memory_agent ON agent_node_memory (agent_id);
