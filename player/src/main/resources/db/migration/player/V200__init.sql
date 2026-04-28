-- owner_id refers to account.players(id); cross-module FK intentionally omitted.
CREATE TABLE agents (
    id         UUID         PRIMARY KEY,
    owner_id   UUID         NOT NULL,
    name       VARCHAR(64)  NOT NULL,
    api_token  VARCHAR(128) NOT NULL UNIQUE,
    class_id   VARCHAR(32),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_agents_owner ON agents (owner_id);

CREATE TABLE agent_profiles (
    agent_id    UUID PRIMARY KEY REFERENCES agents (id) ON DELETE CASCADE,
    max_hp      INT  NOT NULL,
    max_stamina INT  NOT NULL,
    max_mana    INT  NOT NULL
);
