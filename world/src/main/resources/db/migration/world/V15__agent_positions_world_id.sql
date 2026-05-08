-- Denormalize world_id onto agent_positions so the per-tick load can filter
-- by world_id without joining nodes -> regions every tick.
--
-- Deploy assumption: this migration is single-deploy with downtime — old pods
-- writing without world_id would fail the NOT NULL added below. Phase 1 is
-- pre-launch so that's acceptable; revisit when zero-downtime deploys matter.

ALTER TABLE agent_positions ADD COLUMN world_id BIGINT;

UPDATE agent_positions
SET world_id = (
    SELECT r.world_id
    FROM nodes n
    JOIN regions r ON r.id = n.region_id
    WHERE n.id = agent_positions.node_id
)
WHERE world_id IS NULL;

ALTER TABLE agent_positions ALTER COLUMN world_id SET NOT NULL;

ALTER TABLE agent_positions
    ADD CONSTRAINT fk_agent_positions_world
        FOREIGN KEY (world_id) REFERENCES worlds (id);

-- Hot-path index: per-tick load reads "online agents in world w".
CREATE INDEX idx_agent_positions_world_active
    ON agent_positions (world_id) WHERE active;
