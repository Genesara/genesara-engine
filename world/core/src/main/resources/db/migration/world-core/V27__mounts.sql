-- Phase 2 Mounts (#21): per-instance tamed-mount rows + stackable mount cargo
-- + two new agent_item_instances columns (mount-gear binding + mount-cargo
-- stowage) + a third column tagging MOUNT_GEAR ItemCategory.
--
-- Permadeath: a dead mount row is DELETE'd, not flagged. Bookkeeping lives in
-- the MountDied event, not in a `died_at_tick` graveyard.
--
-- No per-agent ownership — mounts are world entities like real-world animals.
-- Anyone in the same node can mount, feed, equip gear on, store cargo in, or
-- attack them. Riding state (mounted_by_agent_id) is the only "is this idle?"
-- signal; the maintenance sweep regenerates fatigue exactly when
-- mounted_by_agent_id IS NULL. Natural limiter is upkeep, not a cap.

-- node_id is a soft reference (no cross-aggregate FK) per the node_buildings
-- convention — mount cleanup on node deletion lands as a future admin op.
CREATE TABLE mounts
(
    mount_id              UUID         NOT NULL,
    mount_type            VARCHAR(64)  NOT NULL,
    node_id               BIGINT       NOT NULL,
    hp_max                INT          NOT NULL CHECK (hp_max > 0),
    hp_current            INT          NOT NULL CHECK (hp_current >= 0),
    hunger_max            INT          NOT NULL CHECK (hunger_max > 0),
    hunger                INT          NOT NULL CHECK (hunger >= 0),
    fatigue_max           INT          NOT NULL CHECK (fatigue_max > 0),
    fatigue               INT          NOT NULL CHECK (fatigue >= 0),
    mounted_by_agent_id   UUID,
    tamed_at_tick         BIGINT       NOT NULL,
    PRIMARY KEY (mount_id),
    CHECK (hp_current <= hp_max),
    CHECK (hunger <= hunger_max),
    CHECK (fatigue <= fatigue_max)
);

CREATE INDEX idx_mounts_node ON mounts (node_id);
-- Hot lookup for the movement reducer's mounted-branch test (`findByRider`).
-- Partial because an idle mount has NULL rider — most rows in steady state.
CREATE UNIQUE INDEX uq_mounts_rider
    ON mounts (mounted_by_agent_id)
    WHERE mounted_by_agent_id IS NOT NULL;

-- Stackable cargo table. Per-instance items stowed on a mount use
-- agent_item_instances.stowed_in_mount_id instead — same shape split as
-- agent_inventory vs agent_item_instances on the agent side.
CREATE TABLE mount_inventory
(
    mount_id    UUID         NOT NULL REFERENCES mounts (mount_id) ON DELETE CASCADE,
    item_id     VARCHAR(32)  NOT NULL,
    quantity    INT          NOT NULL CHECK (quantity > 0),
    PRIMARY KEY (mount_id, item_id)
);

-- (mount_id is left-prefix of the PK, so no separate index needed.)
-- TODO(stage-g): vocabulary CHECK / load-time validator pinning
-- mount_inventory.item_id to stackable RESOURCE items (no EQUIPMENT/KEY/MOUNT_GEAR
-- — those route through agent_item_instances.stowed_in_mount_id instead).

-- Extend agent_item_instances with three nullable bindings:
--   - equipped_on_mount_id / equipped_mount_slot: a MOUNT_GEAR instance equipped
--     onto a mount in a specific slot. Both NULL = item in agent stash.
--   - stowed_in_mount_id: a per-instance item (EQUIPMENT, KEY, MOUNT_GEAR)
--     riding in the mount's cargo hold. Distinct from `equipped_on_mount_id`
--     which is gear; cargo is just goods being carried.
--
-- The CHECK constraints ensure these are mutually exclusive with each other
-- and with the agent-side equipped_in_slot — an item is in exactly one
-- location at a time.
ALTER TABLE agent_item_instances
    ADD COLUMN equipped_on_mount_id  UUID,
    ADD COLUMN equipped_mount_slot   VARCHAR(32),
    ADD COLUMN stowed_in_mount_id    UUID;

-- Drop the V25 vocabulary check (EQUIPMENT, KEY only) by its Postgres
-- auto-generated name and re-state it widened to include MOUNT_GEAR.
-- `IF EXISTS` keeps the jOOQ-codegen H2 path quiet — H2 generates a different
-- system name for V25's inline CHECK, so the named drop is a no-op there.
-- Codegen reads only metadata, not constraint semantics, so the lingering
-- H2-side CHECK doesn't affect the generated tables; Postgres production
-- gets the clean replacement.
ALTER TABLE agent_item_instances
    DROP CONSTRAINT IF EXISTS agent_item_instances_category_check;
ALTER TABLE agent_item_instances
    ADD CONSTRAINT agent_item_instances_category_check
        CHECK (category IN ('EQUIPMENT', 'KEY', 'MOUNT_GEAR'));

ALTER TABLE agent_item_instances
    ADD CONSTRAINT agent_item_instances_mount_slot_vocab
        CHECK (equipped_mount_slot IS NULL OR equipped_mount_slot IN ('SADDLE', 'BARDING', 'HARNESS'));

-- equipped_on_mount_id and equipped_mount_slot are paired: both set or both null.
ALTER TABLE agent_item_instances
    ADD CONSTRAINT agent_item_instances_mount_equip_paired
        CHECK ((equipped_on_mount_id IS NULL) = (equipped_mount_slot IS NULL));

-- Only MOUNT_GEAR rows may carry mount-equip pointers.
ALTER TABLE agent_item_instances
    ADD CONSTRAINT agent_item_instances_mount_gear_paths
        CHECK (
            category = 'MOUNT_GEAR'
            OR (equipped_on_mount_id IS NULL AND equipped_mount_slot IS NULL)
        );

-- An instance is equipped to exactly one place: agent slot, mount slot, or
-- stowed in cargo — or none of the above (stash). At most one of the three
-- bindings may be non-null.
ALTER TABLE agent_item_instances
    ADD CONSTRAINT agent_item_instances_single_location
        CHECK (
            (CASE WHEN equipped_in_slot     IS NULL THEN 0 ELSE 1 END)
          + (CASE WHEN equipped_on_mount_id IS NULL THEN 0 ELSE 1 END)
          + (CASE WHEN stowed_in_mount_id   IS NULL THEN 0 ELSE 1 END) <= 1
        );

-- MOUNT_GEAR must carry the rarity/durability triple like EQUIPMENT, and must
-- NOT carry KEY-style bindings.
ALTER TABLE agent_item_instances
    ADD CONSTRAINT agent_item_instances_mount_gear_required_fields
        CHECK (
            category != 'MOUNT_GEAR' OR (
                rarity IS NOT NULL
                AND durability_current IS NOT NULL
                AND durability_max IS NOT NULL
                AND bound_building_id IS NULL
                AND equipped_in_slot IS NULL
            )
        );

CREATE INDEX idx_agent_item_instances_equipped_mount
    ON agent_item_instances (equipped_on_mount_id)
    WHERE equipped_on_mount_id IS NOT NULL;

CREATE INDEX idx_agent_item_instances_stowed_mount
    ON agent_item_instances (stowed_in_mount_id)
    WHERE stowed_in_mount_id IS NOT NULL;
