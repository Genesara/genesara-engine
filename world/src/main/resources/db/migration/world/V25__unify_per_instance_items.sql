-- Fold per-instance GATE_KEY storage into the equipment-instance table — but
-- with the table renamed to `agent_item_instances`, discriminated by a
-- `category` column. EQUIPMENT and KEY share the same backing table; future
-- per-instance categories (signed crafted items, charged scrolls, named tools)
-- land here without new tables or stores.
--
-- The migration creates the new shape, copies rows over from both legacy
-- tables, then drops the legacy tables. `RENAME TO` is intentionally avoided
-- because the jOOQ DDLDatabase parser used by codegen rejects it; `CREATE +
-- INSERT SELECT + DROP` is the portable form already used elsewhere in this
-- module's history.

CREATE TABLE agent_item_instances
(
    instance_id         UUID         NOT NULL,
    agent_id            UUID         NOT NULL,
    item_id             VARCHAR(32)  NOT NULL,
    category            VARCHAR(16)  NOT NULL,
    created_at_tick     BIGINT       NOT NULL,
    rarity              VARCHAR(16),
    durability_current  INT,
    durability_max      INT,
    creator_agent_id    UUID,
    equipped_in_slot    VARCHAR(32),
    bound_building_id   UUID,
    metadata            JSONB,
    PRIMARY KEY (instance_id),
    -- Vocabulary fences mirror V9/V10 — schema-level defense against a malformed
    -- write poisoning `Rarity.valueOf` / `EquipSlot.valueOf` on read.
    CHECK (category IN ('EQUIPMENT', 'KEY')),
    CHECK (rarity IS NULL OR rarity IN ('COMMON', 'UNCOMMON', 'RARE', 'EPIC', 'LEGENDARY')),
    CHECK (equipped_in_slot IS NULL OR equipped_in_slot IN (
        'HELMET', 'CHEST', 'PANTS', 'BOOTS', 'GLOVES',
        'AMULET',
        'RING_LEFT', 'RING_RIGHT',
        'BRACELET_LEFT', 'BRACELET_RIGHT',
        'MAIN_HAND', 'OFF_HAND'
    )),
    CHECK (durability_current IS NULL OR durability_current >= 0),
    CHECK (durability_max IS NULL OR durability_max > 0),
    CHECK (
        durability_current IS NULL
        OR durability_max IS NULL
        OR durability_current <= durability_max
    ),
    -- EQUIPMENT rows must carry the rarity/durability triple. Phase 4
    -- artifact-tier items will widen this clause as further NOT-NULL fields
    -- join the equipment shape.
    CHECK (
        category != 'EQUIPMENT' OR (
            rarity IS NOT NULL
            AND durability_current IS NOT NULL
            AND durability_max IS NOT NULL
        )
    ),
    -- KEY rows must carry their binding target.
    CHECK (category != 'KEY' OR bound_building_id IS NOT NULL),
    -- KEY rows must NOT spill into equipment-typed columns: defense against a
    -- misrouted insert that would otherwise pass the equipment CHECK trivially
    -- (NOT NULL on the equipment side) yet violate the domain rule that a key
    -- is just an inventory binding.
    CHECK (
        category != 'KEY' OR (
            rarity IS NULL
            AND durability_current IS NULL
            AND durability_max IS NULL
            AND creator_agent_id IS NULL
            AND equipped_in_slot IS NULL
        )
    ),
    CHECK (category != 'EQUIPMENT' OR bound_building_id IS NULL)
);

INSERT INTO agent_item_instances (
    instance_id, agent_id, item_id, category, created_at_tick,
    rarity, durability_current, durability_max, creator_agent_id, equipped_in_slot
)
SELECT
    instance_id, agent_id, item_id, 'EQUIPMENT', created_at_tick,
    rarity, durability_current, durability_max, creator_agent_id, equipped_in_slot
FROM agent_equipment_instances;

INSERT INTO agent_item_instances (
    instance_id, agent_id, item_id, category, created_at_tick, bound_building_id
)
SELECT
    instance_id, agent_id, item_id, 'KEY', created_at_tick, gate_instance_id
FROM agent_keys;

DROP TABLE agent_equipment_instances;
DROP TABLE agent_keys;

CREATE INDEX idx_agent_item_instances_agent ON agent_item_instances (agent_id);
CREATE INDEX idx_agent_item_instances_agent_category ON agent_item_instances (agent_id, category);
-- Hot predicate for `agentHoldsKeyFor(agent, gate)`: partial because KEY is the
-- only category that populates bound_building_id.
CREATE INDEX idx_agent_item_instances_bound_building
    ON agent_item_instances (agent_id, bound_building_id)
    WHERE bound_building_id IS NOT NULL;
-- Partial unique index enforcing "one EQUIPMENT instance per (agent, slot)";
-- mirrors V10's partial-NULL index — KEY rows skip naturally because their
-- slot is always NULL.
CREATE UNIQUE INDEX uq_agent_item_instances_agent_slot
    ON agent_item_instances (agent_id, equipped_in_slot)
    WHERE equipped_in_slot IS NOT NULL;
-- Mirrors V9's creator index for Phase-4 permadeath analytics ("show items
-- signed by this agent"). Partial because most rows in the long term are loot
-- drops or keys (creator_agent_id NULL).
CREATE INDEX idx_agent_item_instances_creator
    ON agent_item_instances (creator_agent_id)
    WHERE creator_agent_id IS NOT NULL;
