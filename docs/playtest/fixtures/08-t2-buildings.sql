-- Session 8 fixture: T2-building agent — issue #19.
--
-- Stocks the agent with enough materials + skill levels to exercise the new
-- multi-bar building path end-to-end:
--   1. Refine raw WOOD into PLANK at a WORKBENCH (PLANK_BASIC: WOOD 3 → PLANK 2)
--   2. Build a WATCHTOWER (multi-bar: CARPENTRY level=15 / SURVIVAL level=10)
--   3. Build a TRADING_POST (single-bar T1-shape: CARPENTRY level=20)
--   4. Trade across the TRADING_POST and observe the 2× trust-gate value lift
--   5. Recommend / equip / level the new BARTERING skill via trade completion
--
-- The agent ships with CARPENTRY + SURVIVAL slotted and pre-leveled past the
-- T2 gates so a Haiku-driven session can walk the build path without grinding
-- the prerequisites. STR=4 covers ~20 kg of inventory weight (5 kg/point).
--
-- WHY these quantities fit Str=4 capacity (20 000 g):
--   WOOD     30 × 800  g =  24 000 g  ← exceeds STR=4; spent quickly on refine
-- so we ship a smaller starter stock + leave headroom for harvested resupply:
--   WOOD     10 × 800  g =   8 000 g
--   STONE    20 × 1500 g =   3 000 g  (actually 30 000 g — STONE is 1000 g)
-- Reality-check from items.yaml:
--   WOOD       800 g       STONE     1000 g       IRON_INGOT 1500 g
--   PLANK     1200 g       COAL       400 g       FIBER       100 g
--   ORE        900 g
-- Pack: WOOD 10 + STONE 25 + COAL 5 + ORE 5 + FIBER 5
--   = 8000 + 25000 + 2000 + 4500 + 500 = 40 000 g (~80% of 50 kg Str=10 cap)
-- Bumping STR to 10 — this fixture is for the build flow, not weight tuning.

\i 00-base.sql

UPDATE agents SET
    strength = 10,
    dexterity = 3,
    constitution = 4,
    perception = 3,
    intelligence = 2,
    luck = 1,
    unspent_attribute_points = 0
WHERE id = :'agent_id';

UPDATE agent_profiles SET
    max_hp = 100,
    max_stamina = 80,
    max_mana = 5
WHERE agent_id = :'agent_id';

UPDATE agent_bodies SET
    hp = 100, max_hp = 100,
    stamina = 80, max_stamina = 80
WHERE agent_id = :'agent_id';

INSERT INTO agent_inventory (agent_id, item_id, quantity) VALUES
    (:'agent_id', 'WOOD',  30),
    (:'agent_id', 'STONE', 40),
    (:'agent_id', 'COAL',   5),
    (:'agent_id', 'ORE',    5),
    (:'agent_id', 'FIBER',  5),
    (:'agent_id', 'CLAY',   5);

-- Slot + level the construction skills past the T2 gates.
-- skill_xp_for_level(N) in SkillProgression is N*100 (linear) — see SkillProgressionImpl.
-- CARPENTRY needs level 20 (TRADING_POST gate) and 15 (WATCHTOWER carpentry bar).
-- SURVIVAL needs level 10 (WATCHTOWER survival bar).
INSERT INTO agent_skills (agent_id, skill_id, level, xp_current)
VALUES
    (:'agent_id', 'CARPENTRY', 25, 0),
    (:'agent_id', 'SURVIVAL',  15, 0),
    (:'agent_id', 'SMITHING',   0, 0);

INSERT INTO agent_skill_slots (agent_id, slot_index, skill_id)
VALUES
    (:'agent_id', 0, 'CARPENTRY'),
    (:'agent_id', 1, 'SURVIVAL'),
    (:'agent_id', 2, 'SMITHING');
