-- Phase 2 cultivated-resources slice: 1:1 row per FARM_PLOT building.
--
-- A row is inserted on FARM_PLOT build completion (BuildReducer side-effect)
-- with all crop fields NULL — the plot exists but is empty. The plant reducer
-- fills (planted_crop, planted_at_tick, last_tended_at_tick); harvest and the
-- per-tick neglect sweep clear them back to NULL. The plot row itself is
-- never deleted while the building stands; ON DELETE CASCADE handles
-- demolition cleanup once the plot's building disappears.
--
-- agent_id and node_id are denormalised from node_buildings so the hot
-- look_around projection can do a single byNodes batch and the per-tick
-- decay sweep can fan events to owners without an extra join.
--
-- agent_id has no cross-module FK to player.agents (matches the convention
-- used by trade_offers / agent_safe_nodes / agent_node_memory).
CREATE TABLE agent_plots
(
    plot_id              UUID         PRIMARY KEY,
    building_instance_id UUID         NOT NULL UNIQUE
                                      REFERENCES node_buildings (instance_id) ON DELETE CASCADE,
    agent_id             UUID         NOT NULL,
    node_id              BIGINT       NOT NULL,
    planted_crop         VARCHAR(48)  NULL,
    planted_at_tick      BIGINT       NULL,
    last_tended_at_tick  BIGINT       NULL,
    -- All three crop fields move together: either the plot is empty (all NULL)
    -- or it carries a planting (all non-NULL).
    CHECK ((planted_crop IS NULL) = (planted_at_tick IS NULL)),
    CHECK ((planted_crop IS NULL) = (last_tended_at_tick IS NULL))
);

CREATE INDEX idx_agent_plots_node  ON agent_plots (node_id);
CREATE INDEX idx_agent_plots_agent ON agent_plots (agent_id);
-- Hot lookup for the per-tick decay sweep: "every plot that currently has
-- something planted." Partial index keeps it tiny — empty plots dominate
-- once farming scales out.
CREATE INDEX idx_agent_plots_planted
    ON agent_plots (last_tended_at_tick)
    WHERE planted_crop IS NOT NULL;
