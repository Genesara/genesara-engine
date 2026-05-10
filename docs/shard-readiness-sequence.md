# Shard Readiness — Implementation Sequence

> Roadmap for the **Multi-pod readiness + Redis perf trims** initiative. All issues tagged [`shard-readiness`](https://github.com/Genesara/genesara-engine/labels/shard-readiness). This document is the canonical sequencing source — issue bodies link back here.

**Designed:** 2026-05-08 (grilling session) · **Status:** Ready for implementation

---

## Progress tracker

> **HOW TO USE.** When the user says "move on to next step", look here. The next unchecked box is the next slice. When a slice merges, tick the box and update **Current step** below. This file is the single source of truth for roadmap position; per-issue acceptance criteria stay tracked inside each GitHub issue.

**Status:** ✅ Initiative complete — all 6 steps merged.

### Sequence

- [x] **Step 1** — [#78](https://github.com/Genesara/genesara-engine/issues/78) Per-world `WorldState` + per-world tick counter *(foundation; biggest single perf win; blocks all others)*
- [x] **Step 2** — [#79](https://github.com/Genesara/genesara-engine/issues/79) Surgical Redis moves: cooldowns, kill streaks, pending attack scales *(blocked by #78; can run in parallel with #80)*
- [x] **Step 3** — [#80](https://github.com/Genesara/genesara-engine/issues/80) Per-world Redis lease (γ) with fenced writes + SIGTERM release *(blocked by #78; can run in parallel with #79)*
- [x] **Step 4** — [#81](https://github.com/Genesara/genesara-engine/issues/81) Parallel per-world tick fan-out via `Dispatchers.IO` *(blocked by #80)*
- [x] **Step 5** — [#82](https://github.com/Genesara/genesara-engine/issues/82) Redis-per-world `CommandQueue` with polymorphic serialization *(blocked by #80; can run in parallel with #81)*
- [x] **Step 6** — [#83](https://github.com/Genesara/genesara-engine/issues/83) Cross-pod pub/sub — `RedisInvalidationBus` for MCP push + static config *(blocked by #82; closes the multi-pod functional gap)*

```
                  ┌──────────────────────┐
                  │ #78 per-world state  │
                  └────────┬─────────────┘
                           │
              ┌────────────┴────────────┐
              ▼                         ▼
   ┌──────────────────────┐  ┌──────────────────────┐
   │ #79 surgical redis   │  │ #80 lease layer (γ)  │
   └──────────────────────┘  └────────┬─────────────┘
                                      │
                          ┌───────────┴───────────┐
                          ▼                       ▼
               ┌──────────────────────┐  ┌──────────────────────┐
               │ #81 parallel ticks   │  │ #82 redis cmd queue  │
               └──────────────────────┘  └────────┬─────────────┘
                                                  │
                                                  ▼
                                       ┌──────────────────────┐
                                       │ #83 invalidation bus │
                                       └──────────────────────┘
```

**Single-pod correctness gate:** every slice ships with single-pod still functional. Multi-pod deployment becomes possible after #82, fully push-correct after #83.

---

## Locked decisions (Q1–Q10)

The grilling session walked the design tree top-down. Each `Q` is the decision point and the locked answer; preserved verbatim so future readers don't have to re-derive the rationale.

| # | Decision |
|---|---|
| **Q1** | **Motive: headroom + sharding-readiness, no measured symptom.** Rank changes by frequency × payload, not gut. |
| **Q2** | **Sharding unit: per-world (i).** No node-sharding within a world. Reducer purity preserved; world boundaries already a hard wall in lore (design principle #2). Node-sharding within a world breaks reducer self-containment for combat, scanning, look_around — that's a rewrite, not "make sure the app supports this." |
| **Q3** | **Pod ownership: (γ) per-world Redis lease**, fenced-token write check at end of every tick. Pods are interchangeable; lease is the lock; no orchestrator required for correctness. Footgun acknowledged: long-GC-pause beyond TTL means the fence check at write is mandatory. |
| **Q4** | **Redis depth: (A) surgical.** DB stays primary for character continuity (bodies, inventory, equipment, perks, skills, slots, recommendations, safe nodes, node memory, buildings, chest contents). Redis takes only state that's volatile-by-nature: cooldowns, kill streaks, pending scales, plus the new tick counter. **Headline perf lever** is filtering the per-tick `WorldStateRepository.load(...)` to *online agents in owned worlds* — pure SQL, no Redis required. |
| **Q5** | **Command intake: (b) Redis-per-world list**, lease holder drains. Submit goes anywhere, decoupling MCP session pod from lease holder. Avoids forcing the future router to be lease-aware. |
| **Q6** | **Cross-pod notifications: (a) Redis pub/sub** — `agent:{id}:notify` for MCP push, `world:{w}:config-invalidate` for `WorldStaticConfig`. Both via a single `RedisInvalidationBus`. Push UX preserved without affinity. |
| **Q7** | **Durability: (γ) hybrid.** Tick counter mirrored to Postgres (`world_tick` table); seeded into Redis as `max(redis, db)` on lease acquire. Cooldowns above `T_persist` write-through to DB (no live cooldown today crosses it; future-proofing against a "1/day perk"). Everything else is Redis-only and intentionally loss-tolerant. Redis runs cache-mode — no AOF, no replica. |
| **Q8** | **Tick concurrency: (ii) parallel fan-out**, coroutine per leased world on `Dispatchers.IO`, joined per cycle. `@Transactional` boundary moves *inside* per-world tick (fails in world A don't roll back world B). |
| **Q9** | **Lease lifecycle: (I) proactive, capped, randomized.** TTL 10s, renew at TTL/2, SIGTERM releases via Lua-atomic compare-and-DEL. `MAX_LEASES_PER_POD` env-configurable. No external orchestrator dependency. |
| **Q10** | **`pendingAttackScales`: (b) Redis with TTL.** Loaded into per-tick `WorldState` slice for online agents. Side benefit: closes the latent expiry-bug noted by the existing TODO (Redis TTL provides the time bound the field never had). |

---

## Pre-existing risks (out of scope, flagged)

These are real issues observed in the current code but explicitly **not** in this initiative's scope. They warrant their own follow-up slices.

- **Reducer-internal Redis writes don't enroll in Postgres tx.** `NodeResourceStore.decrement` (and any future per-reducer Redis side-effect) are not rolled back if the per-world tx fails. The docstring on `WorldTickHandler.onTick` claims they are. Either move side-effects to post-commit or correct the docstring. Not part of this plan; worth a separate slice when prioritised.
- **Long-GC-pause vs. lease TTL.** A pod whose GC pause exceeds TTL keeps reducing for the doomed tick; the fenced check at the very end aborts the write but discarded work isn't free. Mitigation lives in JVM tuning (heap, GC algorithm, lease TTL), not in code.

---

## Non-goals (explicit)

These were considered and rejected during grilling. Do not get talked into them in this batch.

- Moving bodies / inventory / equipment / perks / skills to Redis (Q4 explicitly chose surgical over hot-path mirror).
- Building an orchestrator / router (engine cooperates via leases + Redis routing keys; the router is the user's downstream component).
- Per-pod least-loaded rebalancing beyond the cap (defer; revisit if uneven distribution shows up in metrics).
- Solving the existing Redis-vs-Postgres tx inconsistency.
- Adding any cooldown longer than `T_persist`. If you add one, it write-throughs to DB on arm — make this an in-code assertion in the Redis cooldown store so future cooldowns don't silently bypass the guard.
