-- Flyway repeatable migration. Re-applied automatically when the file's checksum changes.
-- Excluded from jOOQ codegen (OSS edition cannot parse CREATE FUNCTION); called via raw SQL.

-- Drop the prior VARCHAR overload if it exists (legacy schema before BIGINT IDs).
DROP FUNCTION IF EXISTS fn_nodes_within(VARCHAR, INT);

CREATE OR REPLACE FUNCTION fn_nodes_within(origin BIGINT, max_radius INT)
    RETURNS TABLE (node_id BIGINT)
    LANGUAGE SQL
    STABLE
AS $$
    WITH RECURSIVE reachable(n_id, d) AS (
        SELECT origin, 0
        UNION ALL
        SELECT na.to_node_id, r.d + 1
        FROM node_adjacency na
        JOIN reachable r ON na.from_node_id = r.n_id
        WHERE r.d < max_radius
    )
    SELECT DISTINCT n_id FROM reachable;
$$;
