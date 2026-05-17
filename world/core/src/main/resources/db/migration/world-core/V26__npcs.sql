-- Phase 2 Tier-A NPCs: per-instance fauna spawns + per-node last-cleared timestamp.
--
-- Live HP and last-attack-tick are mirrored into Redis-resident WorldState per
-- tick (same shape as agent bodies). This Postgres table owns the spawn-row
-- identity and survives restarts; on reload the in-memory mirror restores HP
-- to hp_max — Tier-A fauna are zone-rest respawning so a "still wounded after
-- a restart" semantic isn't worth the per-tick write traffic.
--
-- aggression_profile is intentionally NOT a column here — it is catalog-driven
-- (npcs.yaml) and lookup-only at tick time. Per-instance overrides are not in
-- scope for this slice; if taming (Phase 2 #21) needs to flip a profile, the
-- column lands then with a real motivating use case.
--
-- spawn_node_id anchors TERRITORIAL aggression: a wandering/fleeing NPC of
-- that profile still knows where its territory is.
CREATE TABLE npcs
(
    npc_id            UUID         NOT NULL,
    npc_type          VARCHAR(64)  NOT NULL,
    node_id           BIGINT       NOT NULL REFERENCES nodes (id),
    spawn_node_id     BIGINT       NOT NULL REFERENCES nodes (id),
    hp_max            INT          NOT NULL CHECK (hp_max > 0),
    hp_current        INT          NOT NULL CHECK (hp_current >= 0),
    spawned_at_tick   BIGINT       NOT NULL,
    last_attack_tick  BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (npc_id),
    CHECK (hp_current <= hp_max)
);

CREATE INDEX idx_npcs_node ON npcs (node_id);
CREATE INDEX idx_npcs_spawn_node ON npcs (spawn_node_id);

-- Lazy-on-entry spawn timer: when an agent enters a node, the move reducer
-- checks `(currentTick - last_cleared_tick) >= npcRespawnTicks` and reseeds
-- if eligible. Default 0 means a never-visited node is always spawn-eligible
-- on first contact — fresh worlds feel alive immediately.
ALTER TABLE nodes
    ADD COLUMN last_cleared_tick BIGINT NOT NULL DEFAULT 0;
