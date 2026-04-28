CREATE TABLE players (
    id             UUID         PRIMARY KEY,
    username       VARCHAR(64)  NOT NULL,
    username_lower VARCHAR(64)  NOT NULL UNIQUE,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE player_credentials (
    player_id     UUID         PRIMARY KEY REFERENCES players (id) ON DELETE CASCADE,
    password_hash VARCHAR(255) NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
