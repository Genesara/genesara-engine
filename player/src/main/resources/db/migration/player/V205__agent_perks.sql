-- Per-agent perk choices. Each row records the binary fork an agent picked at a given
-- skill+milestone (50/100/150). Choices are forever — no UPDATE / DELETE pathway.
--
-- Primary key (agent, skill, milestone_level) enforces the one-pick-per-milestone
-- invariant; the application surfaces a typed rejection on attempted re-pick.
-- skill_id mirrors the VARCHAR(32) shape used in agent_skills for cross-table parity.
CREATE TABLE agent_perks (
    agent_id        UUID        NOT NULL REFERENCES agents(id) ON DELETE CASCADE,
    skill_id        VARCHAR(32) NOT NULL,
    milestone_level INT         NOT NULL CHECK (milestone_level IN (50, 100, 150)),
    perk_id         VARCHAR(64) NOT NULL,
    chosen_at_tick  BIGINT      NOT NULL,
    PRIMARY KEY (agent_id, skill_id, milestone_level)
);

CREATE INDEX idx_agent_perks_agent_skill ON agent_perks (agent_id, skill_id);
