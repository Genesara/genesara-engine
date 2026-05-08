-- Per-agent internal cooldowns for TriggeredPassive perks.
-- TODO(perks-maintenance): expired rows accumulate forever; add a sweep when
-- the perk catalog grows beyond canary scale.
CREATE TABLE agent_perk_cooldowns (
    agent_id            UUID        NOT NULL REFERENCES agents(id) ON DELETE CASCADE,
    perk_id             VARCHAR(64) NOT NULL,
    cooldown_until_tick BIGINT      NOT NULL,
    PRIMARY KEY (agent_id, perk_id)
);
