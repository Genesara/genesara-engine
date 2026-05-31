-- Phase 3 Clan & Faction system (#22). Clans are the persistent agent-grouping
-- above parties; factions are clan-of-clans alliances. Membership lives here and
-- is authoritative; only `clan_rank`/`faction_rank` semantics that the skill-slot
-- formula needs are denormalized onto :player.agents (faction_rank only). Clan
-- node ownership (clan-level) lands with #23 territory.
--
-- Spec: docs/lore/mechanics-reference.md §18. Design: docs/plans/clan-system-22.md.

CREATE TABLE factions
(
    id              UUID        NOT NULL,
    name            VARCHAR(64) NOT NULL,
    founded_at_tick BIGINT      NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT factions_name_unique UNIQUE (name)
);

CREATE TABLE clans
(
    id              UUID        NOT NULL,
    name            VARCHAR(64) NOT NULL,
    -- faction_id references factions(id) (same module). NULL = clan not in a faction;
    -- ON DELETE SET NULL is a backstop — the FactionReducer clears membership explicitly
    -- (it must also sync :player.faction_rank and emit events, which a cascade cannot).
    faction_id      UUID        NULL REFERENCES factions (id) ON DELETE SET NULL,
    founded_at_tick BIGINT      NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT clans_name_unique UNIQUE (name)
);

CREATE INDEX idx_clans_faction ON clans (faction_id) WHERE faction_id IS NOT NULL;

CREATE TABLE clan_members
(
    clan_id        UUID        NOT NULL REFERENCES clans (id) ON DELETE CASCADE,
    -- agent_id refers to :player.agents(id); cross-module FK intentionally omitted.
    agent_id       UUID        NOT NULL,
    clan_rank      VARCHAR(16) NOT NULL,
    -- Per-agent faction rank (rank within the clan's faction). NULL when the clan is
    -- in no faction. Authoritative here; mirrored onto :player.agents.faction_rank for
    -- the skill-slot formula. Populated by the FactionReducer (Slice 4).
    faction_rank   VARCHAR(16) NULL,
    joined_at_tick BIGINT      NOT NULL,
    PRIMARY KEY (clan_id, agent_id),
    -- One clan per agent: the agent appears in at most one clan_members row. The
    -- UNIQUE index also serves the clanOf(agent) lookup hot path.
    CONSTRAINT clan_members_agent_unique UNIQUE (agent_id),
    CHECK (clan_rank IN ('INITIATE', 'SWORN', 'BOUND', 'VANGUARD', 'ARCHON')),
    CHECK (faction_rank IS NULL OR faction_rank IN ('PACT', 'SPEAKER', 'PILLAR', 'SOVEREIGN'))
);
