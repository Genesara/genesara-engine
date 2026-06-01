-- Phase 3 Clan & Faction system (#22): the per-agent faction rank, denormalized
-- onto the agents row so :player's skill-slot formula (computeSlotCount) can add
-- the faction-rank bonus without reaching into :world:clan (which is downstream
-- of :player). NULL = agent not in any faction. The authoritative source is
-- :world.clan_members + clans.faction_id; :world:clan keeps this column in sync
-- via AgentRegistry.setFactionRank on every faction-rank change.
ALTER TABLE agents
    ADD COLUMN faction_rank VARCHAR(16) NULL;
