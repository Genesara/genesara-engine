-- Phase 0 finishing slice: PvP flag per node.
--
-- Defaults TRUE because the open world is PvP-on outside designated green zones.
-- Capital cities and clan homes will flip this to FALSE in Phase 2 / Phase 3 when
-- the zoning systems land. Until then every node is open PvP, matching the canon
-- "anywhere outside a green zone is fair game" rule.
ALTER TABLE nodes
    ADD COLUMN pvp_enabled BOOLEAN NOT NULL DEFAULT TRUE;
