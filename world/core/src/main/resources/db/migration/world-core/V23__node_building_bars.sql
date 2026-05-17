-- Phase 2 buildings slice: per-bar progress for multi-skill construction.
--
-- Every building gets at least one row in this table (single-bar buildings have
-- one entry, multi-bar T2 buildings have two or more). Each `build(skill)` call
-- advances exactly one bar; the aggregate progress on `node_buildings` stays as
-- Σ across bars so the existing `(status='ACTIVE') = (progress=total)` CHECK
-- continues to enforce completion atomically.
--
-- Soft FK to `node_buildings(instance_id)` per the convention established in
-- V13 (`building_chest_inventory`). Orphan cleanup deferred to the future
-- destroy/repair flow.
CREATE TABLE node_building_bars
(
    instance_id    UUID        NOT NULL,
    skill_id       VARCHAR(64) NOT NULL,
    progress_steps INT         NOT NULL DEFAULT 0,
    total_steps    INT         NOT NULL,
    PRIMARY KEY (instance_id, skill_id),
    CHECK (progress_steps >= 0 AND progress_steps <= total_steps),
    CHECK (total_steps > 0)
);

-- No explicit index on `instance_id` alone — the (instance_id, skill_id) PK B-tree
-- already serves leading-column lookups for the byInstance / byInstances reads.
