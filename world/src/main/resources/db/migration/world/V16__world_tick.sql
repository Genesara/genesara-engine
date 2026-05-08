-- Per-world tick counter mirror. Authoritative store is Redis; this row
-- exists for cold-start recovery (see RedisWorldTickCounter).

CREATE TABLE world_tick
(
    world_id   BIGINT      PRIMARY KEY REFERENCES worlds (id) ON DELETE CASCADE,
    tick       BIGINT      NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

INSERT INTO world_tick (world_id, tick)
SELECT id, 0 FROM worlds;
