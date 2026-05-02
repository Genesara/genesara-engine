-- Phase 1 buildings slice: per-instance node-attached structures.
--
-- Per-instance UUID PK rather than a denormalized JSONB column on `nodes`
-- so per-row HP / status / ownership updates (Phase 3 capture, repair) stay
-- O(1) and a "buildings owned by clan X" query can use a B-tree index.
--
-- hp_current / hp_max are forward-compat for the #23 capture mechanic — no
-- slice damages buildings yet. node_id and built_by_agent_id are soft refs
-- (no cross-aggregate FK) per this schema's convention; orphan cleanup on
-- node delete / agent permadeath lands in a future admin tool.
CREATE TABLE node_buildings
(
    instance_id          UUID         NOT NULL,
    node_id              BIGINT       NOT NULL,
    building_type        VARCHAR(32)  NOT NULL,
    status               VARCHAR(20)  NOT NULL,
    built_by_agent_id    UUID         NOT NULL,
    built_at_tick        BIGINT       NOT NULL,
    last_progress_tick   BIGINT       NOT NULL,
    progress_steps       INT          NOT NULL,
    total_steps          INT          NOT NULL,
    hp_current           INT          NOT NULL CHECK (hp_current >= 0),
    hp_max               INT          NOT NULL CHECK (hp_max > 0),
    PRIMARY KEY (instance_id),
    -- Pin the status vocabulary at the schema level so a hand-fixed row cannot
    -- poison BuildingStatus.valueOf(...) on read. The Kotlin enum is the
    -- source of truth; this CHECK is the runtime fence.
    CHECK (status IN ('UNDER_CONSTRUCTION', 'ACTIVE')),
    CHECK (hp_current <= hp_max),
    CHECK (progress_steps >= 0 AND progress_steps <= total_steps),
    -- ACTIVE iff fully built. Prevents two illegal states: an ACTIVE row with
    -- partial progress (partial structure granting full effect) and an
    -- UNDER_CONSTRUCTION row at full progress (completed but not yet effective).
    CHECK ((status = 'ACTIVE') = (progress_steps = total_steps))
);

CREATE INDEX idx_node_buildings_node    ON node_buildings (node_id);
CREATE INDEX idx_node_buildings_builder ON node_buildings (built_by_agent_id);

-- Drives the find-or-create-then-advance lookup in BuildReducer: when an agent
-- calls `build CAMPFIRE`, find their in-progress campfire on this node (if
-- any) and advance it; otherwise insert a new instance. Partial because most
-- rows over time will be ACTIVE — narrow index keeps the lookup cheap.
CREATE INDEX idx_node_buildings_in_progress
    ON node_buildings (node_id, built_by_agent_id, building_type)
    WHERE status = 'UNDER_CONSTRUCTION';
