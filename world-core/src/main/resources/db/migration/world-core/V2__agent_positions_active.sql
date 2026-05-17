ALTER TABLE agent_positions
    ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE;

CREATE INDEX idx_agent_positions_active ON agent_positions (agent_id) WHERE active;
