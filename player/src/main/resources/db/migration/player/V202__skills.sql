-- Per-agent skill state. Three tables, one per concern:
--
--   agent_skills              — XP per (agent, skill). Row created lazily on the
--                               first XP grant; absence == zero XP.
--   agent_skill_slots         — which skills are in which slot. Slots are PERMANENT
--                               (no unequip operation), so this table is INSERT-only
--                               from the application perspective. The unique
--                               constraint on (agent, skill) prevents the same skill
--                               from being slotted twice.
--   agent_skill_recommendations — per-(agent, skill) recommendation counter, capped
--                               in code at 3. `last_recommended_at_tick` drives the
--                               cooldown gate.
CREATE TABLE agent_skills (
    agent_id UUID        NOT NULL REFERENCES agents(id) ON DELETE CASCADE,
    skill_id VARCHAR(32) NOT NULL,
    xp       INT         NOT NULL DEFAULT 0 CHECK (xp >= 0),
    PRIMARY KEY (agent_id, skill_id)
);

CREATE INDEX idx_agent_skills_agent ON agent_skills (agent_id);

CREATE TABLE agent_skill_slots (
    agent_id   UUID        NOT NULL REFERENCES agents(id) ON DELETE CASCADE,
    slot_index INT         NOT NULL CHECK (slot_index >= 0),
    skill_id   VARCHAR(32) NOT NULL,
    PRIMARY KEY (agent_id, slot_index),
    UNIQUE (agent_id, skill_id)
);

CREATE INDEX idx_agent_skill_slots_agent ON agent_skill_slots (agent_id);

CREATE TABLE agent_skill_recommendations (
    agent_id                 UUID        NOT NULL REFERENCES agents(id) ON DELETE CASCADE,
    skill_id                 VARCHAR(32) NOT NULL,
    recommend_count          INT         NOT NULL DEFAULT 0 CHECK (recommend_count >= 0),
    last_recommended_at_tick BIGINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (agent_id, skill_id)
);
