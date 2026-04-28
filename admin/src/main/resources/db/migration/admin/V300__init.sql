CREATE TABLE admins (
    id             UUID         PRIMARY KEY,
    username       VARCHAR(64)  NOT NULL,
    username_lower VARCHAR(64)  NOT NULL UNIQUE,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE admin_credentials (
    admin_id      UUID         PRIMARY KEY REFERENCES admins (id) ON DELETE CASCADE,
    password_hash VARCHAR(255) NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE admin_tokens (
    token        VARCHAR(128) PRIMARY KEY,
    admin_id     UUID         NOT NULL REFERENCES admins (id) ON DELETE CASCADE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    last_used_at TIMESTAMPTZ
);
CREATE INDEX idx_admin_tokens_admin ON admin_tokens (admin_id);
