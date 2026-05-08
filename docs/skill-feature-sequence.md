# Skill Feature — Implementation Sequence

> Roadmap for the **Skills + Perks + Abilities + Classes** expansion. All issues tagged [`skill-feature`](https://github.com/Genesara/genesara-engine/labels/skill-feature). This document is the canonical sequencing source — issue bodies link back here.

**Designed:** 2026-05-08 (grilling session) · **Status:** Ready for implementation

---

## Progress tracker

> **HOW TO USE.** When the user says "move on to next step", look here. The next unchecked box is the next slice. When a slice merges, tick the box and update **Current step** below. This file is the single source of truth for roadmap position; per-issue acceptance criteria stay tracked inside each GitHub issue.

**Current step:** ⏭ **Step 3 — effect-type slices** (Steps 1–2 merged)

### Track A — Phase 1 mechanics + catalog

- [x] **Step 1** — [#62](https://github.com/Genesara/genesara-engine/issues/62) Skill perks: data shape + canary perk *(foundation; blocks all others)*
- [x] **Step 2** — [#63](https://github.com/Genesara/genesara-engine/issues/63) Perk selection flow (events + `select_perk`)
- Step 3 — effect-type slices (any order; can be parallelised by independent contributors):
  - [ ] **Step 3a** — [#64](https://github.com/Genesara/genesara-engine/issues/64) TriggeredPassive system *(soft-coordinates with #15 Combat — see "Combat-coupling note" below)*
  - [ ] **Step 3b** — [#65](https://github.com/Genesara/genesara-engine/issues/65) PassiveAura aggregator
  - [ ] **Step 3c** — [#66](https://github.com/Genesara/genesara-engine/issues/66) Per-level scaling + Modifier perk
  - [ ] **Step 3d** — [#67](https://github.com/Genesara/genesara-engine/issues/67) Active ability system + `use_ability` *(soft-coordinates with #15 Combat)*
- [ ] **Step 4** — [#68](https://github.com/Genesara/genesara-engine/issues/68) Skill+perk catalog v1 *(blocked by all of step 3)*

### Track B — Phase 4 class system

- [ ] **Step 5** — [#31](https://github.com/Genesara/genesara-engine/issues/31) Behavior tracker *(can run in parallel with step 3d / #67; if #31 lands first, hook `use_ability` when #67 adds it)*
- [ ] **Step 6** — [#32](https://github.com/Genesara/genesara-engine/issues/32) Class catalog + `AgentClass` surgery *(blocked by step 5 + step 4)*
- [ ] **Step 7** — [#33](https://github.com/Genesara/genesara-engine/issues/33) Level-10 event + `select_class`
- [ ] **Step 8** — [#34](https://github.com/Genesara/genesara-engine/issues/34) Class evolution L50 + `select_evolution` *(L100 stays deferred — partial-close on this checkbox)*

### Cross-issue ticks (apply when the parent step lands)

- [ ] When #67 (step 3d) merges → tick `use_ability` row in [#5](https://github.com/Genesara/genesara-engine/issues/5); add comment to [#35](https://github.com/Genesara/genesara-engine/issues/35) noting Mana's broadened consumer base.
- [ ] When #68 (step 4) merges → tick the rows in [#5](https://github.com/Genesara/genesara-engine/issues/5) for every new skill that maps to a verb (HUNTING, TRAPS, new combat skills, new craft skills, TRACKING, FIRECRAFT).
- [ ] When #34 (step 8) merges → coordinate naming with [#38](https://github.com/Genesara/genesara-engine/issues/38) for the TECHNICIAN ↔ Drone-Operator overlap.

### Combat-coupling note

Steps 3a (#64) and 3d (#67) have hooks into `AttackReducer`, which is the deliverable of [#15 Phase 2 Combat](https://github.com/Genesara/genesara-engine/issues/15).

- If #15 has **already merged** when we reach step 3a/3d: full scope, all 9 triggers + all active ability effects.
- If #15 has **not merged** yet: scope step 3a to non-combat triggers (`OnHarvestComplete/CraftComplete/BuildComplete/OnLowHp`) and step 3d to non-combat actives (`Self/HealSelf/TeleportNodes`); leave combat triggers / `ScaleNextAttack` for a follow-up tick when #15 lands.

---

## What we're building

A complete progression layer on top of the existing skill module:

1. **51-skill catalog** (up from 17) across 10 categories — gathering, crafting, combat, athletics, survival, knowledge, stealth, social, animal, class-locked.
2. **Perk system** — every skill has 3 milestone perk choices (at levels 50/100/150); each milestone is a binary fork (1-of-2 perks); each perk carries one of 4 effect types.
3. **4 perk effect types** —
   - **`ActiveAbility`** — agent calls `use_ability(...)`, pays HP/Stamina/Mana, ability resolves at next tick, goes on cooldown.
   - **`PassiveAura`** — always-on flat bonus while skill slotted.
   - **`TriggeredPassive`** — fires automatically on one of 9 trigger events (OnHit, OnKill, OnLowHp, OnHarvest, etc.); has internal CD.
   - **`Modifier`** — multiplies the per-level passive scaling rate.
4. **Per-level passive scaling** — every skill declares a `levelEffect: { type, perLevelPct }`; ~+50–75% of primary output by L150, uncapped past 150.
5. **8 base classes** + **24 evolution branches** at L50 — `SOLDIER, SCOUT, HUNTER, ARTISAN, ENGINEER, MEDIC, MERCHANT, RESEARCHER`. Low-magic; near-real-world professions. PSIONICS / MAGE / CLERIC dropped from v1.
6. **Behavior tracker** — 8-axis `ActionCategory` enum drives the level-10 class choice and L50 evolution.

---

## Design decisions (grilled and locked)

| # | Decision | Detail |
|---|---|---|
| 1 | Skill model | **Model C**: passive scaling + perks at milestones (no baseline actives). Existing verbs (`attack`, `harvest`, `craft`, `build`) are the unconditional action layer. |
| 2 | Active abilities | **All from perks** — no skill grants an active at L1. |
| 3 | Per-level scaling | Per-skill custom YAML; bounded `ScalingEffect` enum; slotted-only; ~+50–75% at L150; uncapped past 150. |
| 4 | Perk cardinality | **1 of 2** per milestone. Static (same options for everyone at the same skill+milestone). |
| 5 | Perk effect taxonomy | Closed sealed union: `ActiveAbility | PassiveAura | TriggeredPassive | Modifier`. |
| 6 | Perk discoverability | **Hidden until milestone fires.** Mirror of class-system hidden-until-event. Past picks visible to the agent only. |
| 7 | TriggeredPassive triggers | Closed set of **9**: `OnHitTaken/Dealt/Crit/Kill/LowHp/Dodge`, `OnHarvestComplete/CraftComplete/BuildComplete`. |
| 8 | TriggeredPassive effects | Closed set of **7**: `GrantSelfBuff, HealSelf, DealBonusDamage, ApplyStatusToTarget, TeleportNodes, RefundResource, GrantBonusItem`. |
| 9 | Multi-trigger stacking | All matching triggers fire (no first-match-wins). Each perk has its own internal CD. |
| 10 | ActiveAbility cost | Single resource per ability: `HP | STAMINA | MANA`. Stamina default; Mana for RESEARCHER/MEDIC focus actives (3–5 perks); HP for berserker actives (5–10 perks). |
| 11 | ActiveAbility target | Closed: `Self | SingleAgent (same node) | AreaSelfNode`. Same-node only for v1. |
| 12 | ActiveAbility verb | **Single generic** `use_ability(abilityId, target?)` MCP tool. Not per-ability tools. |
| 13 | Cost timing | Paid at cast; not refunded on miss/dodge. Resolution at tick N+1. |
| 14 | Hard restrictions | **One total**: RESEARCHER cannot wield auto-firearms. Everything else is soft modifiers. |
| 15 | Soft XP modifier | 3-tier per class: 1.5x primary / 1.0x neutral / **0.5x default off-build**. ~10–15 explicit overrides per class. |
| 16 | Damage modifiers | Per class: `Map<DamageType, Float>`, default 1.0. |
| 17 | Behavior fingerprint | 8 axes: `COMBAT, GATHER, CRAFT, BUILD, SOCIAL, EXPLORE, MEDICAL, TRADE`. |
| 18 | Class roster (8) | `SOLDIER, SCOUT, HUNTER, ARTISAN, ENGINEER, MEDIC, MERCHANT, RESEARCHER`. |
| 19 | Class drops | `WARRIOR (→SOLDIER), MAGE, CLERIC, FARMER, COMMANDER, RANGER`. |
| 20 | Evolution branches | 3 per class at L50 (24 total). L100 deferred. |
| 21 | PSIONICS | Dropped from v1 catalog (low-magic). Re-add when psionic class lands. |

---

## Issue map

### New issues — Phase 1 mechanics + catalog (created 2026-05-08)

| # | Issue | Phase | Blocks |
|---|---|---|---|
| [#62](https://github.com/Genesara/genesara-engine/issues/62) | Skill perks: data shape + canary perk | Foundation | All others |
| [#63](https://github.com/Genesara/genesara-engine/issues/63) | Perk selection flow (events + `select_perk`) | Foundation | #64–#68 |
| [#64](https://github.com/Genesara/genesara-engine/issues/64) | TriggeredPassive system (9 triggers, 7 effects) | Effect layer | #68 |
| [#65](https://github.com/Genesara/genesara-engine/issues/65) | PassiveAura aggregator | Effect layer | #68 |
| [#66](https://github.com/Genesara/genesara-engine/issues/66) | Per-level passive scaling + Modifier perk | Effect layer | #68 |
| [#67](https://github.com/Genesara/genesara-engine/issues/67) | Active ability system + `use_ability` tool | Effect layer | #68 |
| [#68](https://github.com/Genesara/genesara-engine/issues/68) | Skill+perk catalog v1 (51 skills, ~300 perks) | Content | — |

### Existing issues — Phase 4 class system (tagged `skill-feature` 2026-05-08)

| # | Issue | Status |
|---|---|---|
| [#31](https://github.com/Genesara/genesara-engine/issues/31) | Behavior tracker | Updated with 8-axis `ActionCategory` decision |
| [#32](https://github.com/Genesara/genesara-engine/issues/32) | Class catalog | Updated with 8-class roster + restrictions + 24 branches |
| [#33](https://github.com/Genesara/genesara-engine/issues/33) | Level-10 event | No design change — confirmed |
| [#34](https://github.com/Genesara/genesara-engine/issues/34) | Class evolution | Updated with 24-branch table; **L100 deferred** |

### Existing issues — touched but not closed

| # | Issue | Touch |
|---|---|---|
| [#5](https://github.com/Genesara/genesara-engine/issues/5) | Skills recommendation-hook coverage | New verbs (`use_ability`, `select_perk`) and new skills (HUNTING, TRAPS, etc.) extend its rolling tracker. Tick rows as #67/#68 land. |
| [#15](https://github.com/Genesara/genesara-engine/issues/15) | Phase 2 Combat | `AttackReducer` is the host for 5 of the 9 TriggeredPassive trigger hooks (#64) and ScaleNextAttack actives (#67). Coordinate sequencing — see Implementation order below. |
| [#35](https://github.com/Genesara/genesara-engine/issues/35) | Psionics | Body needs an update note when #67 lands: Mana now consumed by RESEARCHER/MEDIC actives, not exclusively psionic. **Do not close** — psionics itself is still deferred. |
| [#36](https://github.com/Genesara/genesara-engine/issues/36) | Scanning (Researcher) | SCANNING formalised as the only class-locked skill in v1 (#68 + #32). |
| [#38](https://github.com/Genesara/genesara-engine/issues/38) | Drones (class-locked) | Converges with `ENGINEER → TECHNICIAN` evolution branch (#34). Coordinate naming. |

---

## Implementation order

### Track A — Phase 1 mechanics + catalog

```
#62 ─┬─► #63 ─┬─► #64 ─┐
     │       ├─► #65 ─┤
     │       ├─► #66 ─┤── #68
     │       └─► #67 ─┘
```

**Strict order:**

1. **#62 — Perks: data shape + canary** (foundation; nothing works without this).
2. **#63 — Perk selection flow** (depends on #62; needed before any effect type can be tested end-to-end).
3. **#64–#67** — four effect-type slices, can run in parallel by independent contributors. Each must:
   - Implement its effect type.
   - Ship a canary perk on SWORD using that effect type.
   - Pass integration tests through the canary.
4. **#68 — Catalog v1** (depends on all of #64–#67; this is where the bulk YAML lands).

**Sequencing constraint with Phase 2 Combat (#15):**
- If #15 has not shipped when we start #64, scope #64 to non-combat triggers only (`OnHarvestComplete/CraftComplete/BuildComplete/OnLowHp`) and add a follow-up to wire combat triggers when `AttackReducer` lands.
- Same constraint on #67's combat-impacting actives. Non-combat actives (Self-buff, HealSelf, TeleportNodes) can ship sooner; `ScaleNextAttack` and any direct-damage active needs Combat.

### Track B — Phase 4 class system

```
#31 ──► #32 ──► #33 ──► #34 (L50 only; L100 deferred)
```

**Strict order:**

5. **#31 — Behavior tracker** — needs the 8-axis enum and per-action-reducer counter bumps. Must hook into `use_ability` (from #67) before #67 ships, so order: **#67 → #31** (the COMBAT counter uses the ability fire-points). If #31 ships first, hook the new verb when it lands.
6. **#32 — Class catalog** — `AgentClass` enum surgery (drop 5, rename 1, add 4) + `classes.yaml` expansion + `ClassLookup` + soft-modifier reads + the single hard restriction. Depends on #31 (`behaviorFingerprint` axes) and on **#68** (eligibleSkills reference final skill IDs).
7. **#33 — Level-10 event** — depends on #31 + #32. Mostly a thin orchestrator over those.
8. **#34 — Class evolution L50** — mirror of #33 with sliding-window read. Depends on #33. **L100 stays deferred.**

### Cross-track integration

- **#67 must add an entry to #5** (`use_ability` extends the recommendation hook for skill XP).
- **#68 must tick rows in #5** for every new skill added.
- **#67 must post a comment on #35** noting Mana's broadened consumer base; do not close.
- **#34's TRAPPER branch depends on TRAPS skill from #68** — tick the dependency when #68 lands.
- **#32's TECHNICIAN branch overlaps #38** — coordinate naming/skills.

---

## Test posture per slice

Every slice follows `feedback_implementation_workflow.md`:

1. Implement.
2. Unit + integration tests in the same slice (Testcontainers Postgres for DB-backed work).
3. Invoke `code-quality-reviewer` agent on the changed surface.
4. Iterate until tests green and review surfaces nothing material.
5. **Ask before committing.** Branch + PR (see `feedback_branch_and_pr.md`); never commit straight to main.
6. After merge: tick the matching checkbox in the GitHub issue, plus any cross-issue ticks called out in this doc.

---

## Out of scope

- **PSIONICS** skill and psionic class — deferred until / unless a future "magic" expansion lands.
- **L100 class evolution** — design when L50 lands and behavior is observable.
- **Multi-resource costs** on a single ability — single resource per ability for v1.
- **Multi-node ranged abilities** — same-node only for v1.
- **Channeling / charge-up actives** — future expansion.
- **Ability XP independent of parent skill** — abilities don't level; their effects are fixed per perk.
- **Perk re-rolling** — choices are forever (mirrors class no-respec rule).
- **Perk preview** — perks hidden until milestone fires (mirrors class system).
- **Hybrid / multi-class** — explicitly forbidden by spec §4.

---

## Spec cross-references

- [`docs/lore/mechanics-reference.md` §3 Skills](lore/mechanics-reference.md#3-skills) — milestone model
- [`docs/lore/mechanics-reference.md` §4 Classes](lore/mechanics-reference.md#4-classes) — class system
- [`docs/lore/mechanics-reference.md` §9 Combat Resolution](lore/mechanics-reference.md#9-combat-resolution) — damage formula
- [`docs/lore/mechanics-reference.md` §10 Abilities](lore/mechanics-reference.md#10-abilities-active-vs-passive) — active vs passive
- [`docs/ROADMAP.md`](ROADMAP.md) — phase narrative