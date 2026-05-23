-- Denormalize hot mount-row reads onto the mount row itself.
--
-- Before this migration, every movement reducer step re-fetched the SADDLE
-- gear row (`byEquippedOnMount(...).filter(SADDLE).sum(mountGearBonus)`)
-- and every cargo-store / cargo-take call did the same for HARNESS plus a
-- full cargo+stowed weight scan. Both reads sit inside the per-tick
-- transaction.
--
-- We project the running sums onto the mount row so the hot paths read a
-- single SELECT instead. The denormalized values are kept consistent by:
--   * EquipMountGearService.equipMountGear/unequipMountGear (saddle/harness)
--   * MountCargoServiceImpl.storeResource/storeInstance/takeResource/takeInstance (current_load)
--   * MountDeathCleanup (no-op — mount row is deleted, columns ride along)
--
-- All three columns default to 0 so existing rows backfill to a safe shape
-- even without migration-time scan; the per-mount UPDATE below fills the
-- correct values for any mount that already carries gear or cargo.

ALTER TABLE mounts
    ADD COLUMN saddle_speed_bonus        INT    NOT NULL DEFAULT 0,
    ADD COLUMN harness_cargo_bonus_grams INT    NOT NULL DEFAULT 0,
    ADD COLUMN current_load_grams        BIGINT NOT NULL DEFAULT 0
        CHECK (current_load_grams >= 0);

-- Backfill is intentionally absent. The cold-path readers (EquipMountGearService
-- and MountCargoServiceImpl) write the denormalized values on every state
-- transition starting at the next release, so the only mounts whose row will
-- carry a stale 0 are mounts that were tamed before V30 AND have not been
-- touched since. Those next hit on equip/unequip/store/take corrects the row.
-- This avoids a potentially-slow scan of agent_item_instances during V30 apply
-- in environments where mounts predate the feature; the trade-off is a brief
-- window where pre-migration mounts read a 0-bonus until they next change.
