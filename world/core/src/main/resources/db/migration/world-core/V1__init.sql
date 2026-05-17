CREATE TABLE worlds
(
    id         BIGSERIAL    PRIMARY KEY,
    name       VARCHAR(128) NOT NULL,
    node_count INT          NOT NULL,
    node_size  INT          NOT NULL,
    frequency  INT          NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE regions
(
    id            BIGSERIAL        PRIMARY KEY,
    world_id      BIGINT           NOT NULL REFERENCES worlds (id) ON DELETE CASCADE,
    sphere_index  INT              NOT NULL,
    biome         VARCHAR(32),
    climate       VARCHAR(32),
    centroid_x    DOUBLE PRECISION NOT NULL,
    centroid_y    DOUBLE PRECISION NOT NULL,
    centroid_z    DOUBLE PRECISION NOT NULL,
    face_vertices JSONB            NOT NULL,
    UNIQUE (world_id, sphere_index)
);
CREATE INDEX idx_regions_world ON regions (world_id);

CREATE TABLE region_neighbors
(
    region_id   BIGINT NOT NULL REFERENCES regions (id) ON DELETE CASCADE,
    neighbor_id BIGINT NOT NULL REFERENCES regions (id) ON DELETE CASCADE,
    PRIMARY KEY (region_id, neighbor_id)
);

CREATE TABLE nodes
(
    id        BIGSERIAL    PRIMARY KEY,
    region_id BIGINT       NOT NULL REFERENCES regions (id) ON DELETE CASCADE,
    q         INT          NOT NULL,
    r         INT          NOT NULL,
    terrain   VARCHAR(32)  NOT NULL,
    UNIQUE (region_id, q, r)
);
CREATE INDEX idx_nodes_region ON nodes (region_id);

CREATE TABLE node_adjacency
(
    from_node_id BIGINT NOT NULL REFERENCES nodes (id) ON DELETE CASCADE,
    to_node_id   BIGINT NOT NULL REFERENCES nodes (id) ON DELETE CASCADE,
    PRIMARY KEY (from_node_id, to_node_id)
);

-- agent_id refers to player.agents(id); cross-module FK intentionally omitted.
CREATE TABLE agent_positions
(
    agent_id UUID   PRIMARY KEY,
    node_id  BIGINT NOT NULL REFERENCES nodes (id)
);
CREATE INDEX idx_agent_positions_node ON agent_positions (node_id);

CREATE TABLE agent_bodies
(
    agent_id    UUID PRIMARY KEY,
    hp          INT NOT NULL,
    max_hp      INT NOT NULL,
    stamina     INT NOT NULL,
    max_stamina INT NOT NULL,
    mana        INT NOT NULL,
    max_mana    INT NOT NULL
);
