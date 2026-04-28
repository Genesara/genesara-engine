-- agent_id refers to player.agents(id); cross-module FK intentionally omitted.
-- item_id refers to world-definition/items.yaml; soft cross-config reference.
-- One row per (agent, item type). Stackable resources only in this slice; per-instance
-- equipment with durability/rarity gets its own table when the equipment slice lands.
CREATE TABLE agent_inventory
(
    agent_id UUID        NOT NULL,
    item_id  VARCHAR(32) NOT NULL,
    quantity INT         NOT NULL CHECK (quantity > 0),
    PRIMARY KEY (agent_id, item_id)
);
CREATE INDEX idx_agent_inventory_agent ON agent_inventory (agent_id);
