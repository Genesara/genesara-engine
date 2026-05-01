# Genesara Engine

> An open-source MMORPG engine designed to be played entirely by AI agents — a living, persistent world. No human players control characters; every agent is an autonomous program connecting via MCP. The world runs 24/7.

---

## Project docs

Design and lore live in dedicated files. Reach for them when the task at hand depends on the relevant surface — they are NOT loaded into Claude's context per session, so cite the path when referencing a section.

| Doc                                                                    | Covers                                                                                                                                                                       |
| ---------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| [`docs/lore/mechanics-reference.md`](docs/lore/mechanics-reference.md) | **Canonical** game mechanics spec (stats, skills, classes, combat, death, PvP, psionics, world systems) — the source of truth when CLAUDE.md and an in-code comment disagree |
| [`docs/architecture.md`](docs/architecture.md)                         | Engineering patterns + rationale across modules                                                                                                                              |
| [`docs/mcp-api.md`](docs/mcp-api.md)                                   | MCP API design — two-channel session model, queue-and-ack vs sync-read tools                                                                                                 |
| [`docs/persistence.md`](docs/persistence.md)                           | DB / Redis layout, jOOQ + Flyway conventions                                                                                                                                 |
| [`docs/modules.md`](docs/modules.md)                                   | Module structure (`engine`, `account`, `player`, `world`, `admin`, `api`, `app`)                                                                                             |
| [`docs/auth.md`](docs/auth.md)                                         | Auth model (admin bearer chain, agent API keys)                                                                                                                              |
| [`docs/ROADMAP.md`](docs/ROADMAP.md)                                   | Phase narrative + GitHub-issue pointers per phase                                                                                                                            |

---

## Implementation Workflow

Every implementation slice follows this loop until green. Do not skip steps even when the diff feels small:

1. **Implement the feature** against the approved plan.
2. **Write unit AND integration tests in the same slice** as the production code. Integration tests are not deferred to a follow-up infra PR — if a module needs new test infra (e.g. Testcontainers Postgres), the infra is part of the slice.
3. **Invoke the `code-quality-reviewer` agent** on the changed surface. Treat findings as in-scope.
4. **Repeat 1–3** until tests are green and the review surfaces nothing material.
5. **Ask before committing.** Never run `git commit` or `git push` without explicit approval per slice. The hand-off summarises findings + state and invites the user to commit.
6. **After commit, tick the matching item in its GitHub issue.** Roadmap is tracked in GitHub issues (see [`docs/ROADMAP.md`](docs/ROADMAP.md) for the per-phase index) — find the issue whose section the slice landed against, tick the checkbox the slice covered, and add a one-line note pointing at the commit. Close the issue when its full checklist is ticked. Only after the commit lands.

---

## Tech Stack

| Layer          | Technology                              |
| -------------- | --------------------------------------- |
| Backend        | Kotlin + Spring Boot                    |
| Database       | PostgreSQL (jOOQ + Flyway)              |
| Cache / Queues | Redis                                   |
| Tick Engine    | Kotlin coroutines + scheduler           |
| MCP Server     | Spring AI MCP                           |
| REST API       | Spring Boot + OpenAPI                   |
| Dashboard      | React + WebSocket *(planned)*           |
| Hosting        | AWS ECS + RDS + ElastiCache *(planned)* |
| Auth           | API keys per agent + JWT for admin      |

---

## Key Design Principles

These are the lens for design and review decisions. When in doubt, choose the option that honors more of these.

1. **No UI for agents** — the game world is pure state and structured API responses. The web dashboard is a spectator layer for humans.
2. **Communication requires presence** — agents can only communicate with others in the same node. Information travels at the speed of agents.
3. **Scarcity drives conflict** — non-renewable resources, infrastructure-capped membership, and maintenance costs ensure the world never reaches equilibrium.
4. **Social dependency is mandatory** — single agents hit hard progression walls. Cooperation is not optional.
5. **The class is a behavioral fingerprint** — what you do narrows the level-10 class options the system offers. Agents cannot game this from the start.
6. **Death has real consequences** — XP-bar-driven penalties and de-leveling on empty-bar deaths mean every fight carries meaningful risk, without arbitrary doom timers.
7. **Information asymmetry is a feature** — fog of war, single-class scanning, and map trading mean knowledge is a competitive resource.
8. **Economy is emergent** — the engine ships barter only. Currencies, auctions, and black markets are agent-built infrastructure.
9. **Infrastructure is power** — base tier governs node identity, member capacity, and captureability. Capturing means destroying down the ladder.
10. **Reputation is plural** — local relationships, global Authority, global Fame are independent ledgers. Low Fame strips PvP protection; everyone has incentive to build it.