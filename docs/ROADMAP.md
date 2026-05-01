# Roadmap

> Action items reflect the design decisions captured in [`lore/mechanics-reference.md`](lore/mechanics-reference.md). Numeric values flagged TBD live in that doc's Appendix B and are tuned in playtesting.

The roadmap is tracked in **GitHub issues**. Each issue is one section of one phase (e.g. "Phase 1 — Crafting (basic)") with the section's checklist as the body. As a slice ships, tick the matching item in its issue and add a one-line note pointing at the commit; close the issue when the full checklist is ticked.

| Phase    | Theme               | Filter                                                                                                            |
| -------- | ------------------- | ----------------------------------------------------------------------------------------------------------------- |
| Phase 0  | Foundation          | *Shipped* — see git log (`feat: ... (Phase 0 Slice N)` commits 1–10)                                              |
| Phase 1  | Core Loop           | [`label:phase-1`](https://github.com/Genesara/genesara-engine/issues?q=is%3Aopen+label%3Aphase-1)                 |
| Phase 2  | Social Layer        | [`label:phase-2`](https://github.com/Genesara/genesara-engine/issues?q=is%3Aopen+label%3Aphase-2)                 |
| Phase 3  | Clan & Territory    | [`label:phase-3`](https://github.com/Genesara/genesara-engine/issues?q=is%3Aopen+label%3Aphase-3)                 |
| Phase 4  | Class System        | [`label:phase-4`](https://github.com/Genesara/genesara-engine/issues?q=is%3Aopen+label%3Aphase-4)                 |
| Phase 5  | Open Beta           | [`label:phase-5`](https://github.com/Genesara/genesara-engine/issues?q=is%3Aopen+label%3Aphase-5)                 |
| Phase 6  | Post-Beta Expansion | [`label:phase-6`](https://github.com/Genesara/genesara-engine/issues?q=is%3Aopen+label%3Aphase-6)                 |
| Phase 7+ | V2 (Interplanetary) | [`label:phase-7`](https://github.com/Genesara/genesara-engine/issues?q=is%3Aopen+label%3Aphase-7)                 |

## Phase goals

The elevator-pitch summary of each phase. Full checklists live in the GitHub issues linked above.

- **Phase 0 — Foundation.** Tick engine, world graph, agent core, base MCP layer (`look_around` / `move` / `inspect` / `get_map` / `get_inventory` / `get_status`). *Shipped.*
- **Phase 1 — Core Loop.** First playable solo loop: an agent can survive (gauges + `consume` + `drink`), gather + mine, equip gear with rarity / durability / requirements, die meaningfully (XP-bar penalty + checkpoint respawn), and grow skills via the recommendation loop. *In progress.*
- **Phase 2 — Social Layer.** Multi-agent: communication (proximity-only `say`), trade, combat with five damage types, parties, PvP infrastructure (outlaw status + green zones), cultivated resources, Tier-2 buildings, NPCs, mounts.
- **Phase 3 — Clan & Territory.** Clan + faction entities; the T1–T5 base-tier ladder with destroy-down-the-ladder capture; Tier-3 buildings + autonomous machines; PvP refinements (witness cascade); roads, task boards, vehicles + sea travel.
- **Phase 4 — Class System.** Hidden behavior tracker → level-10 class-choice event → branched evolution. Psionics gated by attribute thresholds; Researcher-class scanning; cartography + map trading; class-locked drones.
- **Phase 5 — Open Beta.** First public release. Public MCP endpoint, hosted agent deployment, billing, dashboard, balance pass, Tier-C boss scheduler.
- **Phase 6 — Post-Beta Expansion.** Modding API, agent SDKs, marketplace, seasonal events.
- **Phase 7+ — V2 (Interplanetary).** Moon / Mars / procedural planets, spaceships + portal gates, alien races, Cosmolinguistics, suzerainty.