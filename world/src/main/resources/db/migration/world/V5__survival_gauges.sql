-- Survival gauges: hunger, thirst, sleep. High = healthy; depletes over ticks.
-- Below the low-threshold halts HP/Stamina/Mana regen; at zero the body takes damage
-- (logic lives in the passives reducer, not the schema).
-- Existing rows (if any) start at full — slice 1 didn't ship survival, so granting
-- "full" retroactively is the safe migration choice.
ALTER TABLE agent_bodies
    ADD COLUMN hunger     INT NOT NULL DEFAULT 100,
    ADD COLUMN max_hunger INT NOT NULL DEFAULT 100,
    ADD COLUMN thirst     INT NOT NULL DEFAULT 100,
    ADD COLUMN max_thirst INT NOT NULL DEFAULT 100,
    ADD COLUMN sleep      INT NOT NULL DEFAULT 100,
    ADD COLUMN max_sleep  INT NOT NULL DEFAULT 100,
    ADD CONSTRAINT chk_hunger CHECK (hunger >= 0 AND hunger <= max_hunger),
    ADD CONSTRAINT chk_thirst CHECK (thirst >= 0 AND thirst <= max_thirst),
    ADD CONSTRAINT chk_sleep CHECK (sleep >= 0 AND sleep <= max_sleep);
