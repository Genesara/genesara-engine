-- Stage E of Phase 2 Mounts (#21): mount-gear equip enforcement.
--
-- Partial unique index on `(equipped_on_mount_id, equipped_mount_slot)` —
-- mirrors `uq_agent_item_instances_equipped_in_slot` for the agent side.
-- A single MOUNT_GEAR row may occupy any given (mount, slot) pair; the
-- service layer pre-checks via byEquippedOnMount but this is the
-- authoritative race fence and is translated to `SLOT_OCCUPIED` on
-- 23505 unique-violation by the calling service.

CREATE UNIQUE INDEX uq_agent_item_instances_equipped_mount_slot
    ON agent_item_instances (equipped_on_mount_id, equipped_mount_slot)
    WHERE equipped_on_mount_id IS NOT NULL;
