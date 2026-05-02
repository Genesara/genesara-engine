ALTER TABLE players
    ADD COLUMN api_token VARCHAR(128);

UPDATE players
SET api_token = 'plr_' || REPLACE(gen_random_uuid()::text, '-', '')
WHERE api_token IS NULL;

ALTER TABLE players
    ALTER COLUMN api_token SET NOT NULL;

ALTER TABLE players
    ADD CONSTRAINT players_api_token_key UNIQUE (api_token);
