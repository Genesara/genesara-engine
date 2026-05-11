-- Session 2 fixture: agent with crafting materials and unspent attribute points.
-- Targets: allocate_points, equip_skill (after a recommendation), craft, build,
-- set_safe_node. Equipment slice (equip_item / unequip_slot) needs an item
-- instance in agent_equipment_instances — none seeded here because the
-- catalog has no equippable weapons/armor yet (world-definition/items.yaml).
--
-- WHY these quantities fit Str=1 capacity (5000 g; 5 kg per Str point,
-- balance/Lookup.kt CARRY_GRAMS_PER_STRENGTH_POINT):
--   WOOD  2 × 800  g = 1600 g
--   STONE 1 × 1500 g = 1500 g
--   BERRY 5 × 50   g =  250 g
--   HERB  5 × 30   g =  150 g
--   FIBER 5 × 100  g =  500 g
--   HIDE  1 × 600  g =  600 g
--                     ------
--                      4600 g (≈92% of capacity; ~400 g harvest headroom)
-- Covers Phase-1 reachable crafts (FIBER_BASIC: WOOD 1, CLOTH_BASIC: FIBER 3).
-- LEATHER_BASIC (HIDE 2) stays dormant in v1 — HIDE is non-harvestable.

\i 00-base.sql

INSERT INTO agent_inventory (agent_id, item_id, quantity) VALUES
    (:'agent_id', 'WOOD',   2),
    (:'agent_id', 'STONE',  1),
    (:'agent_id', 'BERRY',  5),
    (:'agent_id', 'HERB',   5),
    (:'agent_id', 'FIBER',  5),
    (:'agent_id', 'HIDE',   1);
