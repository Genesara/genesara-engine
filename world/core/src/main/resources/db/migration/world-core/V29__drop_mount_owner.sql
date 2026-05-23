-- PR #191 review: drop mount ownership entirely. Mounts are world entities
-- like real-world animals — anyone in the same node can mount, feed, equip
-- gear on, store cargo in, or attack them. Natural limiter is upkeep, not
-- a per-agent cap. The `release`/`claim` verbs and the mount-cap balance
-- values go away with this column.

ALTER TABLE mounts DROP COLUMN owner_agent_id;

DROP INDEX IF EXISTS idx_mounts_owner;
