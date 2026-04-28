-- race_id refers to player-definition/races.yaml; soft cross-module reference (no FK).
-- Existing rows (none in prod yet) are retroactively granted 5 unspent attribute points.
-- Acceptable per Phase 1 Slice 1 plan; revisit before the next migration if real users exist.
ALTER TABLE agents
    ADD COLUMN race_id                  VARCHAR(32) NOT NULL DEFAULT 'human_commoner',
    ADD COLUMN level                    INT         NOT NULL DEFAULT 1,
    ADD COLUMN xp_current               INT         NOT NULL DEFAULT 0,
    ADD COLUMN xp_to_next               INT         NOT NULL DEFAULT 100,
    ADD COLUMN unspent_attribute_points INT         NOT NULL DEFAULT 5,
    ADD COLUMN strength                 INT         NOT NULL DEFAULT 1,
    ADD COLUMN dexterity                INT         NOT NULL DEFAULT 1,
    ADD COLUMN constitution             INT         NOT NULL DEFAULT 1,
    ADD COLUMN perception               INT         NOT NULL DEFAULT 1,
    ADD COLUMN intelligence             INT         NOT NULL DEFAULT 1,
    ADD COLUMN luck                     INT         NOT NULL DEFAULT 1;
