-- Phase 2 cultivated-resources slice: 1:1 row per FARM_PLOT building.
--
-- A row is inserted on FARM_PLOT build completion and removed only when the
-- building is demolished (ON DELETE CASCADE). The plot itself has no owner —
-- any agent at the node can plant, tend, and harvest. `planted_by_agent_id`
-- tracks WHICH agent planted the current crop so the per-tick neglect sweep
-- can route `CropDied` back to them; it is NOT an access-control field.
--
-- node_id is denormalised from node_buildings so the hot look_around
-- projection can do a single byNodes batch and the per-tick decay sweep can
-- iterate without an extra join.
--
-- agent_id values have no cross-module FK to player.agents (matches the
-- convention used by trade_offers / agent_safe_nodes / agent_node_memory).
CREATE TABLE agent_plots
(
    plot_id              UUID         PRIMARY KEY,
    building_instance_id UUID         NOT NULL UNIQUE
                                      REFERENCES node_buildings (instance_id) ON DELETE CASCADE,
    node_id              BIGINT       NOT NULL,
    planted_crop         VARCHAR(48)  NULL,
    planted_at_tick      BIGINT       NULL,
    last_tended_at_tick  BIGINT       NULL,
    planted_by_agent_id  UUID         NULL,
    -- All four crop fields move together: either the plot is empty (all NULL)
    -- or it carries a planting (all non-NULL).
    CHECK ((planted_crop IS NULL) = (planted_at_tick IS NULL)),
    CHECK ((planted_crop IS NULL) = (last_tended_at_tick IS NULL)),
    CHECK ((planted_crop IS NULL) = (planted_by_agent_id IS NULL))
);

CREATE INDEX idx_agent_plots_node ON agent_plots (node_id);
-- Hot lookup for the per-tick decay sweep: "every plot that currently has
-- something planted." Partial index keeps it tiny — empty plots dominate
-- once farming scales out.
CREATE INDEX idx_agent_plots_planted
    ON agent_plots (last_tended_at_tick)
    WHERE planted_crop IS NOT NULL;
