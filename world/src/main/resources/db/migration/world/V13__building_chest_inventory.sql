-- Phase 1 buildings slice: per-instance storage-chest contents.
--
-- Mirrors the agent_inventory shape: one row per (chest, item) with a stack
-- quantity. STORAGE_CHEST is the only consumer in Phase 1 (personal owner-
-- only stash). Future chest variants — CLAN_STORAGE_CHEST in Phase 3, banker
-- buildings, drop-boxes — reuse this table by writing rows keyed on their
-- own building_id; access semantics live in the per-variant reducer, not
-- here.
--
-- building_id is a soft reference (no FK) to node_buildings(instance_id) —
-- matches the convention. Orphan-row cleanup is the responsibility of a
-- future destroy/repair flow that touches the parent building.
CREATE TABLE building_chest_inventory
(
    building_id UUID        NOT NULL,
    item_id     VARCHAR(32) NOT NULL,
    quantity    INT         NOT NULL CHECK (quantity > 0),
    PRIMARY KEY (building_id, item_id)
);

CREATE INDEX idx_building_chest_inventory_building ON building_chest_inventory (building_id);
