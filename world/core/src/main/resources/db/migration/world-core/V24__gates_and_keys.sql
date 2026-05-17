-- Phase 2 defensive cluster + extraction infrastructure: gate state and per-agent keys.
--
-- GATE buildings carry an OPEN/CLOSED state independent of their construction
-- lifecycle. Stored in a 1:1 sidecar table rather than a column on
-- `node_buildings` so non-gate building rows pay no storage cost and the
-- shared `Building` domain type stays gate-agnostic.
--
-- A row is inserted on GATE build completion (status flips to ACTIVE) and
-- removed via ON DELETE CASCADE when the parent building row is destroyed.
-- Default state is FALSE (CLOSED) — a freshly-built gate locks itself; the
-- builder uses `toggle_gate` (key in hand) to open it.
CREATE TABLE building_gate_states
(
    building_instance_id UUID    PRIMARY KEY
                                 REFERENCES node_buildings (instance_id) ON DELETE CASCADE,
    is_open              BOOLEAN NOT NULL DEFAULT FALSE
);

-- Per-instance GATE_KEY storage. Mirrors `agent_equipment_instances`: one row
-- per physical key, bound to the gate it opens. Keys are stackless inventory
-- items — they cannot merge because each instance is identified by the
-- (gate_instance_id) it targets.
--
-- The builder receives 1 key automatically on gate build completion. Copies
-- are minted via the `copy_gate_key` verb at a WORKBENCH (requires holding a
-- matching template key, not consumed). Lost / stolen keys are recoverable
-- only by destroying the gate and rebuilding it.
--
-- agent_id has no cross-module FK to player.agents (matches the convention
-- used by node_buildings / agent_equipment_instances / agent_node_memory).
-- gate_instance_id is a soft FK to node_buildings; orphan cleanup is the
-- responsibility of the future destroy/repair flow.
CREATE TABLE agent_keys
(
    instance_id      UUID         NOT NULL,
    agent_id         UUID         NOT NULL,
    item_id          VARCHAR(32)  NOT NULL,
    gate_instance_id UUID         NOT NULL,
    created_at_tick  BIGINT       NOT NULL,
    PRIMARY KEY (instance_id)
);

CREATE INDEX idx_agent_keys_agent ON agent_keys (agent_id);
CREATE INDEX idx_agent_keys_gate  ON agent_keys (gate_instance_id);
CREATE INDEX idx_agent_keys_agent_gate ON agent_keys (agent_id, gate_instance_id);
