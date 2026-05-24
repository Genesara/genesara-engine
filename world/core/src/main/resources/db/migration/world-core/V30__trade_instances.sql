-- Trade v2: extend trade offers with per-instance item UUIDs.
--
-- The stackable swap (offered/requested JSONB maps) covers materials by type+qty.
-- Equipment, keys, and mount gear are per-instance items keyed by UUID — they
-- now travel in their own JSONB array columns alongside the stackable maps.
--
-- DEFAULT '[]'::jsonb keeps the existing PENDING rows decodable post-migration
-- without a backfill pass — they simply present empty instance sets to the
-- respond reducer.
ALTER TABLE trade_offers
    ADD COLUMN offered_instances   JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN requested_instances JSONB NOT NULL DEFAULT '[]'::jsonb;
