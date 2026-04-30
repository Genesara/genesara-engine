-- Phase 1 equipment-grid slice: track which slot (if any) an instance is equipped in.
--
-- The slot is a property of the instance row rather than a separate join table:
-- one instance lives in at most one slot at a time, the relationship has no
-- additional state of its own, and reading "what's equipped" is a single
-- predicate scan on the existing `agent_equipment_instances` table. Persisting
-- this on a separate `agent_equipment_slots` table would require a join on every
-- read (and an FK constraint to keep the two in sync) for no behavioral gain.
--
-- The CHECK constraint pins the slot vocabulary at the schema level. Without it
-- a malformed write (admin tool typo, future bypass writer) would cause
-- `EquipSlot.valueOf(...)` to throw on every read of the affected agent's
-- equipment — same poison-on-read failure mode the V9 migration prevented for
-- `rarity`.
--
-- The partial unique index enforces "one instance per (agent, slot)" — equipping
-- a second item to a slot that already has one is a constraint violation, not a
-- read-modify-write race in the reducer. Partial because most rows in the long
-- term are unequipped (sitting in the agent's stash); a non-partial unique would
-- collide on every NULL row pair.
--
-- Two-handed weapons logically occupy both hands but only fill the MAIN_HAND row.
-- The OFF_HAND lock is enforced at the service layer (the equip reducer reads
-- the main-hand item and checks `twoHanded`) — putting it in SQL would require
-- correlated subqueries on every write and make the constraint hard to read.
--
-- Migration safety: this migration is fine as-is *because* the table is empty
-- in production today. If this V10 ever needs to be backported onto a
-- populated `agent_equipment_instances` table, prefer the staged form:
--   1. ALTER TABLE ... ADD COLUMN ... — metadata-only on Postgres 11+.
--   2. ALTER TABLE ... ADD CONSTRAINT ... CHECK (...) NOT VALID; — skips the
--      table scan; you can VALIDATE CONSTRAINT in a follow-up.
--   3. CREATE UNIQUE INDEX CONCURRENTLY ... — avoids the table-level lock.

ALTER TABLE agent_equipment_instances
    ADD COLUMN equipped_in_slot VARCHAR(32);

ALTER TABLE agent_equipment_instances
    ADD CONSTRAINT agent_equipment_instances_slot_check
    CHECK (equipped_in_slot IS NULL OR equipped_in_slot IN (
        'HELMET', 'CHEST', 'PANTS', 'BOOTS', 'GLOVES',
        'AMULET',
        'RING_LEFT', 'RING_RIGHT',
        'BRACELET_LEFT', 'BRACELET_RIGHT',
        'MAIN_HAND', 'OFF_HAND'
    ));

CREATE UNIQUE INDEX uq_agent_equipment_instances_agent_slot
    ON agent_equipment_instances (agent_id, equipped_in_slot)
    WHERE equipped_in_slot IS NOT NULL;
