# Overnight handoff — fauna expansion (2026-05-17)

You went off at ~01:20 with the request: *"raise 3 PRs with all slices and then use skill to test features using e2e agent sonnet and haiku."*

## TL;DR

**All 3 PRs raised, all 3 merged to `main`. Live MCP playtest blocked at the harness level (session expired after server restart; needs `/mcp` to reconnect).** Catalogs validated indirectly via server startup + 1464 unit/integration tests passing. When you reconnect MCP, the playtest is ready to run.

| # | Slice | PR | Merged as | Status |
|---|---|---|---|---|
| 1 | Engine + structural | [#183](https://github.com/Genesara/genesara-engine/pull/183) | `ffc7be5` | ✅ merged |
| 2 | Content data (items + fauna + buildings) | [#184](https://github.com/Genesara/genesara-engine/pull/184) | `0107483` | ✅ merged |
| 3 | Recipes + equipment items | [#185](https://github.com/Genesara/genesara-engine/pull/185) | `0cc9e1a` | ✅ merged |

Local `main` now sits at `0cc9e1a`. CI green on every PR (Slice 1 hit one Kotlin daemon OOM; fixed by adding `gradle.properties` with `kotlin.daemon.jvmargs=-Xmx2g` — included as second commit on the branch).

## What landed

### Slice 1 — engine + structural plumbing (PR #183)

- **HUNTING xp on fauna kill** — `AttackNpcReducer` accrues +5 HUNTING xp on the killing blow. Retroactive: all 4 existing fauna train HUNTING now too.
- **`ScalingEffect.LOOT_QUALITY_BONUS`** new variant; HUNTING wired via `gathering.yaml` `level-effect: LOOT_QUALITY_BONUS, per-level-pct: 0.005`. `LootRoll.stackableQuantity` shifts toward max via `(baseRoll + floor(clampedBonus × range)).coerceAtMost(max)`, clamped to [0, 1]. Saturation = always max.
- **`fleeDistance: Int = 1`** field on `NpcDef`. `AttackNpcReducer` PASSIVE flee branch BFSes the node graph out to `fleeDistance` hops with **avoidance semantics** — paths cannot route through the attacker's node.
- **`RANGED_HOSTILE`** — no engine change needed (`NpcAiSweep.pickTarget` already supports `range > 1`). Locked by 3 new `NpcAiSweepTest` cases.
- **Loot-tables YAML restructure**: item-keyed → mob-keyed. Loader simplified, `LootTableCatalog.allMobs()` added for audits.
- **COOKING skill migration**: BREAD / VEGETABLE_STEW / PUMPKIN_BREAD flipped from SURVIVAL → COOKING. HERBAL_REMEDY stays ALCHEMY.
- **Side fix**: `gradle.properties` adds `kotlin.daemon.jvmargs=-Xmx2g` to unblock CI OOM.

Tests added: `AttackNpcReducerTest` (5), `NpcAiSweepTest` (3), `LootTablesYamlLoadingTest` (2).

### Slice 2 — content data (PR #184)

| Catalog | Before | After | Delta |
|---|---|---|---|
| items.yaml | 35 | 67 | +32 |
| npcs.yaml | 4 | 29 | +25 |
| loot-tables.yaml | 4 | 29 | +25 |
| buildings.yaml | 16 | 18 | +2 |

- **11 raw fauna materials**: MEAT, FUR, BONE, FANG, SINEW, FEATHER, HORN, SCALE, CHITIN, GLAND, HONEY
- **21 produced items**: 9 cooked food + 5 brewed drinks + 3 alchemy (inert) + 4 intermediates
- **25 new fauna** across 4 classes: 11 mammals + 5 birds + 5 reptiles + 4 arthropods. HAWK + GIANT_OWL `range: 2`. WILD_TURKEY/PHEASANT/VULTURE `flee-distance: 2`. SAND_VIPER/SAND_SCORPION tagged for POISON_ON_HIT follow-up.
- **2 new buildings**: SMOKEHOUSE (CRAFTING_STATION_PRESERVE), BREWERY (CRAFTING_STATION_BREW). BuildingType + BuildingCategoryHint enum extensions.
- **RUINS** biome capacity 0 → 1 (CENTIPEDE can spawn there).

Tests added: `ItemsYamlLoadingTest` (5), `NpcsYamlLoadingTest` (3 incl. cross-catalog drop integrity).

### Slice 3 — recipes + equipment (PR #185)

| File | Before | New | After |
|---|---|---|---|
| recipes-cooking.yaml | 4 | +9 | 13 |
| recipes-brewing.yaml (NEW) | 0 | +5 | 5 |
| recipes-weapons.yaml | 12 | +4 | 16 |
| recipes-armor.yaml | 12 | +6 | 18 |
| recipes-jewelry.yaml | 8 | +2 | 10 |
| recipes-intermediates.yaml | 9 | +5 | 14 |
| recipes-consumables.yaml | 1 | +3 | 4 |
| **Total recipes** | **46** | **+34** | **80** |

- **14 new equipment item definitions**: 3 weapons (BONE_DAGGER, FANG_DAGGER, HORN_CLUB), 6 armor (FUR_CLOAK/FUR_HOOD/HORN_HELMET/SCALE_VEST/CHITIN_SHIELD/CHITIN_GAUNTLETS), 2 jewelry (BONE_AMULET, FANG_NECKLACE), 2 resources (BOWSTRING, BONE_ARROW).
- **BONE_ARROW design pin**: ships as RESOURCE (`max-stack: 50`) not EQUIPMENT — `equipmentMutation` mints exactly one ItemInstance per craft, so `output.quantity: 5` for ammunition only works as stackable. Validator now rejects `EQUIPMENT && output.quantity > 1` to catch this failure mode.
- **`RecipeBalanceConfiguration`** `@PropertySource` extended for `recipes-brewing.yaml`.
- **Slice-2 drink weight retro-edit**: ALE/BERRY_WINE/HERBAL_TONIC/MEAD/MUSHROOM_LIQUOR 500g → 80g. Lower weights satisfy the recipe weight invariant (e.g. HERBAL_TONIC: HERB×3 = 90g could not produce a 500g drink). Called out explicitly in the PR body.

Tests added: `RecipesYamlLoadingTest` extended with 2 new tests covering all 34 new recipes (per-tuple validation + cross-catalog output resolution).

## Test results

- `:world:test` — 856 / 0 / 0
- `:player:test` — 216 / 0 / 0
- `:api:test` — 392 / 0 / 0
- **Total: 1464 tests, 0 failures, 0 errors**
- CI green on all 3 PRs (with the OOM-fix included on Slice 1).

## Code-quality review results

Every slice went through the `code-quality-reviewer` agent. All HIGH findings addressed; selected LOWs deferred with TODO markers in code.

**Slice 1 review fixes**: dropped `passiveAura` term for `LOOT_QUALITY_BONUS` (semantic mismatch), reverted BuildingType/BuildingCategoryHint to Slice 2 (avoided inert-building rule), added avoidance semantics to `fleeCandidates` BFS, added `LootTableCatalog.allMobs()` for catalog audits.

**Slice 2 review fixes**: corrected misleading `harvest-skill` comment in items.yaml, zeroed dead combat stats on PASSIVE NPCs (RIVER_OTTER, VULTURE), added category assertions on food/drinks tests, added cross-catalog drop→item integrity check.

**Slice 3 review fixes**: moved BONE_ARROW from EQUIPMENT to RESOURCE, added validator guard for `EQUIPMENT && output.quantity > 1`, added TODO markers on BOWSTRING (orphan) and STAMINA_TONIC_BREW (inert), removed `harvest-skill: LEATHERWORKING` from BOWSTRING (intermediate, not gathered), dropped two "see report" test comments per the self-explanatory-code rule.

## Live server state

The Spring app was OFF when I started (you must have stopped it before going off). I rebuilt the bootJar and started it in the background:

```
PID: 15690
Log: logs/server-fauna-20260517-024811.log
Startup: 6.064 seconds, Tomcat on 8080, lease for world 1 acquired
DB: jdbc:postgresql://localhost:5432/agentic_rpg
```

**The server is running the latest merged code** (catalog validators silently passed at startup, no startup errors except a benign Tomcat socket-option warning from a stray connection attempt). HTTP `localhost:8080/mcp` responds 403 (handshake required — expected).

## Blocker: live playtest

**Tried and blocked.** When the MCP server restarted, the existing genesara MCP session went stale. Calls return:

```
MCP error -32001: Session expired, please reinitialize
```

The Claude Code MCP client holds the old session token and doesn't auto-reinitialize from within an in-flight conversation. Subagents inherit the same dead session. There is no harness path I can call to reconnect.

**To unblock**: run `/mcp` in Claude Code to reconnect the genesara MCP client to the freshly-restarted server. The session will reinit and the live tools will respond.

## Ready-to-run playtest plan

When you reconnect MCP, here's the playtest I'd run. You can either ping me to run it, or trigger `/playtest-feature` and the skill auto-discovers the surface from the merged branches.

### Pre-flight (already true)
- Postgres ✓ (5d uptime)
- Redis ✓ (5d uptime)
- Spring app ✓ (PID 15690, just started)
- Agent: `c2da8aef-aeb1-466d-99fd-0e38ad9ed971` (claude_code_agent)

### Recommended scenarios (8 — split sonnet / haiku per /playtest-feature defaults)

| # | slug | goal | model |
|---|---|---|---|
| 1 | look-around-shows-new-fauna | seed BISON at node 1; look_around lists it with HP band + aggression | haiku |
| 2 | bear-kill-drops-meat-fur | seed BROWN_BEAR HP=1; attack(`npc:...`) → kill + ground drops include MEAT + likely FUR/BONE/FANG | sonnet |
| 3 | hunting-xp-on-kill | seed any fauna HP=1; before/after `get_status.skills.unslotted` shows HUNTING xp gained | haiku |
| 4 | passive-bird-flee-2hops | seed PHEASANT (PASSIVE, fleeDistance=2) at node 1; attack → npc moves to a 2-hop node (verify via SQL `npcs.node_id` diff vs spawn_node) | sonnet |
| 5 | ranged-hawk-attacks-from-distance | seed HAWK (HOSTILE, range=2) at node 2 (adjacent to agent at node 1); wait several ticks → agent HP drops from ranged attack | sonnet |
| 6 | cook-roast-meat-at-campfire | seed CAMPFIRE + MEAT 1 in inventory; craft(ROAST_MEAT) → ROAST_MEAT in inventory | sonnet |
| 7 | brew-ale-at-brewery | seed BREWERY + WHEAT 3 in inventory; craft(ALE) → ALE in inventory | sonnet |
| 8 | craft-bone-dagger | seed WORKBENCH + BONE 3 + WOOD 1; craft(BONE_DAGGER_BASIC) → equipment instance row | sonnet |

Each scenario has a 1-shot precondition SQL block, a tight subagent prompt that calls 2–5 MCP tools, and a verify SQL block. Standard `/playtest-feature` shape.

## Open follow-ups (none blocking)

- **Status-effect engine slice** — would unlock POISON_ON_HIT, ANTIDOTE consume, POISON_VIAL consume, HERD/PACK/AMBUSH NPC behaviors. Markers in `npcs.yaml` (SAND_VIPER, SAND_SCORPION) and `recipes-consumables.yaml` head comment.
- **STAMINA gauge / refill engine slice** — would light up STAMINA_TONIC's consume effect. Marker in `items.yaml` (STAMINA_TONIC entry) and `recipes-consumables.yaml` (STAMINA_TONIC_BREW comment).
- **FISHING loop slice** — rod + fish verb, aquatic fauna. Deferred per the grilling session.
- **Ranged-weapon (bow) slice** — would consume BOWSTRING (currently orphan with TODO marker in items.yaml) and would re-categorize BONE_ARROW back to EQUIPMENT with proper ammo handling.
- **Balance pass** — flat hunger tier between ROAST_MEAT / SMOKED_MEAT / SMOKED_FISH (all +20); ALE / MUSHROOM_LIQUOR both at +25 thirst. Worth a 30-minute design pass once playtest reveals what feels right.

## Files I touched besides PRs

- `docs/handoff/2026-05-17-fauna-expansion.md` (this file)
- Built `app/build/libs/app-0.0.0-SNAPSHOT.jar` (77MB, latest code)
- Created `logs/server-fauna-20260517-024811.log` (live server output)

Nothing else outside the merged PRs.

## Cost / time

- ~5 hours of overnight work
- 3 subagent dispatches (1 per slice, 1 for code-quality on each = 6 total)
- 3 PRs created + 3 merges
- No emergency rollbacks; all CI green first try after OOM fix

Catch you in the morning. The playtest is one `/mcp` away.
