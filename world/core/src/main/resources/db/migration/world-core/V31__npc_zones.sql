-- Phase 2 admin overlay: operator-defined per-region / per-node spawn rules
-- that override the catalog's biome-driven density without editing the
-- catalog YAML. A zone never overrides the catalog's `spawnBiomes` filter
-- (zones tune *how much* spawns, not *whether the biome allows it*); the
-- LazyNpcSpawn reducer enforces that intersection at seed time.
--
-- Resolution at seed time is node first, then region — a NODE zone defined
-- inside a REGION-zoned region wins. When no zone resolves, the seeder falls
-- back to the biome-driven nodeNpcCapacity behaviour verbatim.
CREATE TABLE npc_zones
(
    zone_id           UUID         NOT NULL,
    world_id          BIGINT       NOT NULL REFERENCES worlds (id),
    scope             VARCHAR(16)  NOT NULL,
    region_id         BIGINT       NULL REFERENCES regions (id),
    node_id           BIGINT       NULL REFERENCES nodes (id),
    weights           JSONB        NOT NULL,
    max_concurrent    INT          NOT NULL CHECK (max_concurrent >= 0),
    respawn_ticks     INT          NULL CHECK (respawn_ticks IS NULL OR respawn_ticks >= 0),
    active            BOOLEAN      NOT NULL DEFAULT TRUE,
    created_by        UUID         NOT NULL,
    created_at_tick   BIGINT       NOT NULL,
    PRIMARY KEY (zone_id),
    CHECK (scope IN ('REGION', 'NODE')),
    CHECK ((scope = 'REGION' AND region_id IS NOT NULL AND node_id IS NULL)
        OR (scope = 'NODE'   AND node_id   IS NOT NULL AND region_id IS NULL))
);

CREATE INDEX idx_npc_zones_world  ON npc_zones (world_id);
CREATE INDEX idx_npc_zones_region ON npc_zones (region_id) WHERE region_id IS NOT NULL;
CREATE INDEX idx_npc_zones_node   ON npc_zones (node_id)   WHERE node_id   IS NOT NULL;
