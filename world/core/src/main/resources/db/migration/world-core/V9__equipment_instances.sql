-- Phase 1 foundation slice: per-instance equipment storage.
--
-- Stackable resources stay in `agent_inventory` (one row per (agent, item)
-- with a quantity column). Equipment items — weapons, armor, tools, anything
-- with rarity / durability / a creator signature — live here, one row per
-- physical instance.
--
-- The table is empty at slice land time: nothing in the catalog yet sets
-- `maxDurability`, and no slice yet writes to it. The schema lands now so the
-- equipment-slot slice (12-slot grid + equip/unequip tools) can plug into it
-- without a schema change in the same PR. Same rationale as `agent_node_memory`
-- shipping with `get_map`: slice the schema alone, wire behavior later.
--
-- agent_id intentionally has no FK to player.agents — cross-module FKs are
-- avoided in this schema (matches agent_positions / agent_bodies / agent_node_memory).
-- creator_agent_id likewise: the creator may be a different agent that no
-- longer exists by the time the item is inspected (death + permadeath in
-- Phase 4); we want the signature to outlive the creator.
--
-- TODO(phase-5): wire cleanup on permadeath; otherwise rows leak when an
-- agent is hard-deleted.
CREATE TABLE agent_equipment_instances
(
    instance_id         UUID         NOT NULL,
    agent_id            UUID         NOT NULL,
    item_id             VARCHAR(32)  NOT NULL,
    rarity              VARCHAR(16)  NOT NULL,
    durability_current  INT          NOT NULL CHECK (durability_current >= 0),
    durability_max      INT          NOT NULL CHECK (durability_max > 0),
    creator_agent_id    UUID,
    created_at_tick     BIGINT       NOT NULL,
    PRIMARY KEY (instance_id),
    -- Pin the rarity vocabulary at the schema level so a hand-fixed row or a future
    -- bypass writer cannot poison `Rarity.valueOf(...)` on read and break listByAgent
    -- for an entire agent. The Kotlin enum is the source of truth; this CHECK is a
    -- runtime fence that mirrors the Kotlin contract.
    CHECK (rarity IN ('COMMON', 'UNCOMMON', 'RARE', 'EPIC', 'LEGENDARY')),
    CHECK (durability_current <= durability_max)
);

CREATE INDEX idx_agent_equipment_instances_agent ON agent_equipment_instances (agent_id);

-- Partial index on creator_agent_id: Phase-4 permadeath analytics will need to ask
-- "show items signed by this agent" at scale. Adding the index now (table empty)
-- avoids an online build on a populated table later. Partial because most rows in
-- the long term will be loot drops (creator_agent_id NULL).
CREATE INDEX idx_agent_equipment_instances_creator
    ON agent_equipment_instances (creator_agent_id)
    WHERE creator_agent_id IS NOT NULL;
