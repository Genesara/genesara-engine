-- Per-agent recipe ledger backing the hybrid recipe-discovery model (issue #146).
-- Only class-perk and item-learned recipes are persisted here; `open` recipes
-- stay catalog-side and are visible via skill-level computation at read time.
--
-- One row per (agent, recipe) — duplicate `select_perk` / `consume` is idempotent
-- via the PK. `source` is CLASS_PERK | ITEM_LEARNED and `source_ref` is the perk
-- id or item id that taught it (used for audit + future "where did I learn this"
-- projections).
--
-- agent_id has no cross-module FK to player.agents (matches the pattern in
-- agent_safe_nodes / agent_node_memory). Ledger never rolls back on de-level or
-- perk-loss — once learned, the recipe stays.
CREATE TABLE agent_known_recipes
(
    agent_id          UUID         NOT NULL,
    recipe_id         VARCHAR(64)  NOT NULL,
    source            VARCHAR(32)  NOT NULL,
    source_ref        VARCHAR(64)  NOT NULL,
    learned_at_tick   BIGINT       NOT NULL,
    PRIMARY KEY (agent_id, recipe_id)
);

CREATE INDEX idx_agent_known_recipes_agent ON agent_known_recipes (agent_id);
