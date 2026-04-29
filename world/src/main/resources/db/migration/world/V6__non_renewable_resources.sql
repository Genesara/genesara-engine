-- Persistent overlay for resources where `items.regenerating = false` (ORE, COAL,
-- GEM, SALT, STONE). Live state lives in Redis (NodeResourceStore); rows here mirror
-- the Redis cell for non-renewable items only, so depletion survives a Redis flush
-- or server restart. Renewables are NOT persisted: a Redis flush is a "world reset"
-- for them, which is fine because they would top back up to initial via lazy regen
-- anyway.
--
-- Updated by RedisNodeResourceStore on every decrement of a non-renewable item.
-- Read by RedisNodeResourceStore.seed before writing the Redis cell — if a row
-- exists, the persisted (quantity, initial_quantity) wins over the spawn roll, so
-- a re-paint can never resurrect a mined-out deposit.
CREATE TABLE non_renewable_resources (
    node_id          BIGINT      NOT NULL REFERENCES nodes(id) ON DELETE CASCADE,
    item_id          VARCHAR(32) NOT NULL,
    quantity         INT         NOT NULL CHECK (quantity >= 0),
    initial_quantity INT         NOT NULL CHECK (initial_quantity >= 0),
    PRIMARY KEY (node_id, item_id),
    CHECK (quantity <= initial_quantity)
);

CREATE INDEX idx_non_renewable_resources_node ON non_renewable_resources (node_id);
