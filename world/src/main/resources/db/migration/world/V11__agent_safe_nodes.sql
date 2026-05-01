-- Phase 1 death-system slice: per-agent checkpoint / "safe node".
--
-- An agent calls the `set_safe_node` MCP tool while at a node to mark it as
-- their respawn point. On death, the `respawn` MCP tool reads this row and
-- materializes the agent there. Falls back to the race-keyed starter node
-- (and ultimately to a random spawnable node) when an agent has never set
-- a checkpoint.
--
-- One row per agent (PK on agent_id) — checkpoints overwrite, so an agent
-- always has at most one current safe node. The longer-term spec
-- (CLAUDE.md mechanics-reference) anticipates clan homes and cities as
-- additional safe-node sources in Phase 3; those will plug in alongside this
-- table without changing its shape (the gateway can layer the precedence).
--
-- ON DELETE CASCADE on node_id: if an admin deletes a node, the safe-node
-- row follows rather than orphan. Agents whose checkpoint disappeared get
-- the starter-node fallback on their next respawn — same shape as never
-- having set one.
--
-- agent_id has no cross-module FK to player.agents (matches the pattern in
-- agent_positions / agent_bodies / agent_node_memory). TODO(phase-5): wire
-- explicit cleanup on permadeath when that lands.
CREATE TABLE agent_safe_nodes
(
    agent_id     UUID    NOT NULL,
    node_id      BIGINT  NOT NULL REFERENCES nodes (id) ON DELETE CASCADE,
    set_at_tick  BIGINT  NOT NULL,
    PRIMARY KEY (agent_id)
);

CREATE INDEX idx_agent_safe_nodes_node ON agent_safe_nodes (node_id);
