# ADR 0004: Admin tool — resolutions for five open design questions

**Status:** Accepted — 2026-05-26

## Context

Issue [#199](https://github.com/Genesara/genesara-engine/issues/199) collects the open design questions blocking the Phase 2 admin-tool slices (#198, #200, #202, #209). Each question has a recommended answer in the issue body; this ADR pins those answers so the blocked slices can start without re-litigating them. The decisions are small but cross-cutting — they touch bootstrap, persistence, Redis sizing, frontend auth, and scheduler control — so a single ADR keeps them discoverable as one cohesive admin-tool foundation.

## Decisions

### 1. Sentinel admin agent bootstrap — Flyway with a fixed UUID

A Flyway migration inserts `ADMIN_SENTINEL` with a hardcoded UUID at app boot, before any admin endpoint can run. Admin-placed buildings, NPC spawns, and chest authoring set this id as the owner/creator. The migration is idempotent (`INSERT … ON CONFLICT DO NOTHING`), so re-runs and multi-instance boots are safe.

Rejected alternative: first-write lazy insert. That path needs distributed-lock or unique-index coordination to avoid the first-admin-call race and makes the sentinel id non-deterministic across environments, complicating fixtures and audit-log review.

Applies to: #200 (sentinel bootstrap is on its acceptance criteria), every later admin slice that needs an owner agent.

### 2. Audit retention — keep forever in v1

`world.admin_audit_log` has no TTL, no scheduled prune. Each row is a small JSON insert; admin throughput is bounded by operator hands, not agent loops, so write volume stays low. Revisit when the table crosses ~10M rows or when query latency on the dashboard's paged read degrades — at that point a date-partitioned archive table is a one-migration follow-up.

Rejected alternative: 90-day TTL. Throws away forensics value (e.g. "who edited this building three months ago when the playtest forked") for storage savings the v1 scale doesn't need.

Applies to: #198 (audit log substrate) — no retention job in the slice.

### 3. `admin:feed` Redis backlog cap — 5000 entries

The Redis list `admin:feed` is capped at 5000 events via `LTRIM` after each append. That is 5× the per-agent log's 1000-cap, giving operators meaningful scrub-back when the dashboard reconnects without letting the list grow unbounded between admin sessions. The TTL pattern from `RedisAgentEventLog` carries over: the list expires when no admin is connected.

Tune in the #202 slice once event throughput is measured in playtest. If 5000 covers less than a minute of activity under realistic load, raise it; the cap is a single constant, not a contract.

Rejected alternatives:
- **Same 1000 as the per-agent log.** Too tight for god-view scrub-back — one busy tick can roll the entire window.
- **10k–50k.** Speculative without throughput data; commits memory we haven't justified.

Applies to: #202 (live feed slice).

### 4. Dashboard auth — bearer in memory only

The admin bearer token lives in a React module-level variable (or a `useState` hook in the auth provider). It is never written to `localStorage`, `sessionStorage`, or a cookie. A page reload drops the token and the operator re-logs in. SSE auth uses the same in-memory token via query string or `Authorization` header (whichever Spring accepts cleanly with EventSource).

Rationale: admin sessions are short-lived and supervised (a human operator at a console), so re-login on reload is acceptable. Keeping the token out of persistent storage removes the largest XSS exfiltration vector without any session-management infrastructure.

Rejected alternative: `localStorage` persistence. Survives reload but exposes the token to any XSS bug for the lifetime of the storage entry; not worth the convenience for an internal admin surface.

Applies to: #209 (dashboard v1).

### 5. Tick controls (pause / step / set speed) — defer

Pause / step / set-tick-speed endpoints stay out of the v1 admin tool. The capability is tracked under the dev-controls stretch in [#210](https://github.com/Genesara/genesara-engine/issues/210); when an operator pain-point earns it, it gets promoted to its own issue.

Rationale: not on the critical path for the v1 slices (#198–#209), and shipping it correctly requires a dev-profile gate so it never lands in a production binary. Both the gating mechanism and the operator UX warrant their own design pass when prioritised.

Rejected alternative: ship a feature-flagged version now. Adds scheduler-coupling complexity to the admin surface for capability that today's playtests don't need.

Applies to: #210 (stretch tracker) — checkbox stays as-is.

## Consequences

- **#198** ships without a retention job and without a prune endpoint. The migration adds the table only.
- **#200** depends on the Flyway migration from decision (1); the slice can wire the sentinel id straight into building-creation defaults.
- **#202** uses `5000` as the `LTRIM` length and treats it as tunable, not contractual. Reference this ADR in the constant's comment.
- **#209** wires the auth provider to in-memory storage only; no `localStorage` reads or writes in the diff.
- **#210** keeps the tick-controls box; no new issue filed.

## References

- Issue [#199](https://github.com/Genesara/genesara-engine/issues/199) — the design-question collection this ADR closes
- Blocked slices: [#198](https://github.com/Genesara/genesara-engine/issues/198), [#200](https://github.com/Genesara/genesara-engine/issues/200), [#202](https://github.com/Genesara/genesara-engine/issues/202), [#209](https://github.com/Genesara/genesara-engine/issues/209)
- Stretch tracker: [#210](https://github.com/Genesara/genesara-engine/issues/210)
