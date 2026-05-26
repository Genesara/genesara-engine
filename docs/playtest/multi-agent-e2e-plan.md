# Multi-agent E2E Playtest Plan — Phase 1 + 2

> Goal: spawn 4–5 AI agents that play Genesara through the MCP layer end-to-end, exercise every Phase 1 + Phase 2 surface, surface bugs, and produce a balance memo. **No direct DB after registration.** Bootstrap-only DB writes; everything else flows through `mcp__genesara__*` tools.

---

## 1. Objectives

1. **Coverage.** Touch every shipped MCP tool in `api/.../mcp/tools/*` (49 tools as of `main@3fc542e`).
2. **Cooperation.** Two agents form a party, build a mini-base together (shelter + crafting station + farm plot + storage chest), survive together over multiple in-game cycles.
3. **PvP duel.** Two agents leave the green zone, one flags outlaw, fight to first death, respawn at safe node.
4. **Game-feel report.** Per-agent journal: what was frustrating, what was missing, what was broken, where the grind was excessive or trivial.
5. **Balance memo.** Concrete proposals — "perk X is unreachable in N minutes", "stamina drains 2× faster than recipes consume it", "MOUNTAIN_PASS terrain blocks every harvest verb" — tagged with severity.

Non-goals: Phase 3 (clans, factions, territory, T3 buildings), Phase 4 (class system, scanning, drones). Don't simulate them; if a campaign step requires them, log the gap and continue.

---

## 2. Constraints (load-bearing)

- **MCP only after bootstrap.** Direct DB allowed only for: (a) player + agent registration, (b) read-only verify queries. **No level/gear seeding anywhere.** Any other DB write = bug in plan, escalate to user before executing.
- **One teammate drives one character.** Orchestrated as a Claude Code **Agent Team** (`CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS=1`), not via the Agent tool. Each teammate has its own context window, its own bound MCP server (`mcp__genesara-pt-<name>__*`), and its own `X-Agent-Id`. Teammates communicate with each other and with the lead via the team messaging system; coordination flows through the shared task list.
- **No `--no-verify`-style shortcuts in game.** Teammates must respect rejections. If `move` returns `NotAdjacent`, they call `look_around` and pick a real neighbour — they don't retry blindly or skip with SQL.
- **Bounded budget.** Each teammate capped at **~80 tool calls per campaign phase**. If a teammate hits the cap without completing its goal, that itself is a finding ("level 1 → first crafted spear takes >80 tool calls — grind too steep").
- **One verdict per teammate per phase.** Teammate reports back in a fixed shape (Section 8); the lead compiles the cross-agent report. Teammate task status can lag — lead actively re-checks shared task list before closing a campaign.

---

## 3. Cast — 5 characters, 5 roles

| Slug      | Race           | Role                         | Class lean (level-10 hint)   | Starting node     | Model        | Notes                                                  |
| --------- | -------------- | ---------------------------- | ---------------------------- | ----------------- | ------------ | ------------------------------------------------------ |
| **alice** | human_highland | Gatherer / Builder           | Builder branch               | `human_highland`  | Sonnet 4.6   | Heavy harvest + extract + build; donates resources     |
| **bob**   | human_steppe   | Crafter / Cultivator         | Artisan branch               | `human_steppe`    | Sonnet 4.6   | Runs crafting station + farm plot; high INT/DEX        |
| **carol** | human_commoner | Fighter / Tamer              | Warrior / Beastmaster branch | `human_commoner`  | Sonnet 4.6   | High STR/CON; tames a mount; runs combat scenarios     |
| **dave**  | human_highland | Explorer / Trader / Leader   | Wanderer branch              | `human_highland`  | Sonnet 4.6   | Forms party, ferries goods, leads PvP duel as outlaw   |
| **eve**   | human_steppe   | Wildcard (PvP target)        | Any                          | `human_steppe`    | Sonnet 4.6   | Sparring partner for dave; reactive role (survive A, trade-partner B, take hits C) |

> If 4 agents is preferred, drop **eve** and have **carol** be dave's duel opponent.

The roles overlap deliberately — Genesara's class system is hidden behavior tracking, so we want each character's tool use to weight toward different level-10 branches and observe whether the offered classes match.

---

## 4. Pre-flight (run in parallel; abort on any failure)

```
docker ps --format '{{.Names}}'                        # postgres + redis Up
curl -sf -o /dev/null -w '%{http_code}\n' :8080/mcp     # Spring reachable
PGPASSWORD=agentic_rpg psql -h localhost -U agentic_rpg -d agentic_rpg -c 'select 1'
```

Do **not** auto-start docker/Spring. Surface the failure verbatim and stop.

---

## 5. Bootstrap procedure (one-time, scripted)

The skill runner runs this once before campaigns start. **This is the only DB-touching step.**

### 5.1 Register one player via REST

```bash
curl -sS -X POST :8080/api/players \
  -H 'Content-Type: application/json' \
  -d '{"username":"playtest_runner","password":"playtest_runner_pw_2026"}'
# → { "playerId": "...", "apiToken": "plr_…", "token": "<jwt>" }
```

Save `apiToken` and the JWT.

### 5.2 Register 5 agents under that player via REST

For each name in `[alice, bob, carol, dave, eve]`:

```bash
curl -sS -X POST :8080/api/agents \
  -H "Authorization: Bearer <jwt>" \
  -H 'Content-Type: application/json' \
  -d '{"name":"alice"}'
# → { "agentId": "<uuid>" }
```

Capture the 5 agent UUIDs.

### 5.3 Wire 5 MCP server entries

Today's `~/.claude.json` already had `genesara` + `genesara-bob` belonging to an earlier setup. To avoid clobbering them, the 5 playtest entries are added under a `pt-` prefix: `genesara-pt-alice`, `genesara-pt-bob`, `genesara-pt-carol`, `genesara-pt-dave`, `genesara-pt-eve`. All five carry the same `plr_e719…` token (the `playtest_runner` player) and distinct `X-Agent-Id` headers. **Restart Claude Code** to pick them up.

The teammates inherit the lead's MCP servers — i.e. all 5 servers are visible to every teammate. The lead's job at spawn is to tell each teammate **which server it is bound to** (`"use only mcp__genesara-alice__* tools"`). The teammate prompt enforces this; cross-binding would be a bug.

> Open question — does the user want me to write that config diff, or do they patch `.claude.json` themselves? **Recommendation: I write the patch, user pastes + restarts**, because the global config is outside the project's permission scope.

### 5.4 Enable Agent Teams

Add to `~/.claude/settings.json`:

```json
{ "env": { "CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS": "1" } }
```

…and restart Claude Code (combined with the MCP-entry restart, so one restart covers both).

---

## 6. Campaigns

Three campaigns, in series. The lead opens a campaign by pushing tasks onto the team's shared task list (one task per teammate, plus cross-cutting tasks like "rendezvous at node X"). Teammates pull tasks, act, and report. The lead closes the campaign when all per-teammate tasks are `completed` (or stop conditions in §11 fire).

### Campaign A — Solo survival (≈ 80 tool calls per agent)

**Goal: each agent independently reaches level 3, harvests + crafts a basic weapon, builds 1 Tier-1 building, doesn't starve.**

5 teammates run in parallel, each bound to one MCP server. Each gets a goal sheet pushed via team messaging:

```
GOAL: reach level 3, craft any equipment item, build one tier-1 building you can ACTIVE,
      survive (don't die or starve). Use only MCP tools. Stop after 80 tool calls or goal.
PRIMARY VERBS for your role: <look_around, move, harvest, consume, drink, ... >
EVENT STREAM: read agent://self/events after each tool call; correlate causedBy.
REPORT: per-tool one-liner + final VERDICT + 3-bullet journal.
```

Coverage targets per agent: `spawn`, `look_around`, `move`, `get_status`, `get_map`, `inspect` (node + agent + building), `harvest`, `consume`, `drink`, `pickup`, `craft`, `build`, `equip_item`, `allocate_points`, `equip_skill`, `select_perk`, `set_safe_node`, `get_loadout`, `get_recipes`. ~19 tools.

**Findings to watch for:**
- Does the **skill recommendation cooldown** push useful skills onto agents fast enough? (Issue #5, *Phase 1 — Skills (recommendation-hook coverage)*, is still open.)
- Hunger/thirst drain vs harvest tempo — does the level-1 grind keep agents barely alive, comfortably alive, or trivially alive?
- Are starter terrains rich enough to support 5 agents in 3 nodes? (`human_highland`, `human_steppe`, `human_commoner` all point at node 524 — collision risk).

### Campaign B — Cooperation + mini-base (≈ 100 tool calls per agent)

**Goal: alice + bob + carol + dave converge on one node, party up, build a 4-building mini-base (Shelter + Crafting Bench + Farm Plot + Storage Chest), plant a crop, store shared resources, end with two agents inside the safe footprint. Sub-goal: specialise-and-trade accelerates progression vs solo-grind — each agent harvests one resource type and trades to the others rather than every agent grinding everything alone.**

This is the meatiest campaign. Coverage adds: `say` (3 volumes), `party_invite`, `party_respond`, `get_party`, `kick_member`, `leave_party`, `plant`, `tend`, `extract`, `deposit_to_chest`, `withdraw_from_chest`, `toggle_gate`, `trade_offer`, `trade_respond`, `inspect_npc`, `get_relationships`, `use_ability`. ~17 more tools.

The lead pushes a sequenced sub-scenario list to the shared task list (B1 → B10), each task tagged with the responsible teammate(s) and dependencies. Teammates coordinate **in-game** via `say` and **out-of-game** via team messaging when they need to verify intent (e.g. "I've placed the chest at node 524, deposit your wood there"). Both channels matter: in-game `say` is what we're stress-testing; team messaging is the safety net so they don't deadlock.

| #   | Slug                       | Subagents involved          | Tool sequence (high-level)                                                                                                  |
| --- | -------------------------- | --------------------------- | --------------------------------------------------------------------------------------------------------------------------- |
| B1  | rendezvous                 | alice, bob, dave            | each walks to agreed node; alice broadcasts via `say(SCREAM)`                                                                |
| B2  | party-form                 | dave, alice, bob            | dave invites; alice + bob respond ACCEPT                                                                                     |
| B3  | specialise-and-trade       | alice, bob, carol           | alice `harvest(CLAY)` (no-hostile neighbour); bob `harvest(WOOD)`; carol `harvest(FOOD)` / forage; each `trade_offer` to the others so all three end with balanced inventory — target: 30% the tool calls a solo run would take |
| B4  | base-foundations           | alice, bob                  | alice builds Shelter (uses traded clay); bob builds Crafting Bench (uses traded wood)                                        |
| B5  | farm-plot                  | bob                         | bob `build(FARM_PLOT)`, then `plant(WHEAT)`, then `tend`                                                                     |
| B6  | shared-storage             | alice, bob, dave            | alice `build(STORAGE_CHEST)`; trio `deposit_to_chest` raw resources                                                          |
| B7  | trade-meal                 | alice, bob                  | alice ↔ bob `trade_offer` raw → cooked food (round-trip)                                                                     |
| B8  | crafted-gear-trade         | bob, carol, alice           | bob `craft` starter weapon → `trade_offer` to carol (who needs it for combat); alice `craft` containers → `trade_offer` to bob |
| B9  | npc-encounter              | carol                       | carol approaches a Tier-A NPC; tries `inspect_npc` + `attack` / `tame`                                                       |
| B10 | mount-up                   | carol                       | carol `tame` a tameable mount; `mount`; `equip_transport_gear`; `move`                                                       |
| B11 | base-defence-drill         | dave, alice                 | dave `toggle_gate` (if gate built); party member tries to walk through                                                       |
| B12 | streak-recovery            | one volunteer + party       | one agent deliberately dies; party member loots; verify XP-bar penalty                                                      |

Findings to watch for:
- **Formation buff** — does the party reducer actually apply it; can subagents tell?
- **Specialise-and-trade efficiency** — does B3 actually cut total tool calls vs each agent grinding solo? Measure (alice clay + bob wood + carol food + 3 trades) against a hypothetical solo baseline.
- **Trade v2 instances** — does swapping equipment instances (B8 crafted gear) preserve durability + creator signature?
- **Cultivation tick cadence** — is the wheat ready in a reasonable horizon, or do we wait 200 tool calls?
- **STORAGE_CHEST owner-only access** — does an outsider get rejected on `withdraw_from_chest`?
- **Mount maintenance + gauges** — what verb returns the mount's hunger/sleep? Is it surfaced at all?

### Campaign C — PvP duel (≈ 40 tool calls per agent)

**Goal: dave + eve (or dave + carol) walk into a `pvp_enabled=TRUE` node, one initiates `attack`, both fight, one dies, attacker's `outlaw_state` flips, respawn at safe node, defender's relationship score drops.**

> No level/gear seeding — the duellists fight with whatever they earned in A + B (likely level 2–4, partial perks, hand-crafted gear). The trade-off (chosen by user): we test the raw low-level combat experience, **not** the upper ability + equipment math. If the duel feels broken at this level, that itself is a finding.

Coverage adds: `attack` (agent-vs-agent), `unspawn`, `respawn`, `use_ability` in combat, `get_relationships` before/after, `inspect(target=AGENT)` mid-fight to read HP. ~6 more tools (some re-exercise of Campaign A/B coverage).

Sub-scenarios:

> **World note.** Every node in this run is flipped to `pvp_enabled=TRUE` at bootstrap (user accepted world disposability). Campaign C therefore picks any neighbour of the agents' current node — there is no green zone to step out of. The "leaving green" step degenerates to "walk one node" and we focus on the outlaw transition / damage formula instead.

| #   | Slug                | Notes                                                                                         |
| --- | ------------------- | --------------------------------------------------------------------------------------------- |
| C1  | enter-duel-node     | both walk to an agreed node (any node — all are PvP); verify `look_around` perceives both    |
| C2  | first-strike        | dave `attack(eve)`; verify outlaw transition on event stream + `agents.outlaw_state`          |
| C3  | trade-blows         | both use `use_ability` (granted by perks chosen in Campaign A/B); record damage formula gaps  |
| C4  | first-blood         | one dies; record drop list, XP-bar penalty, `set_safe_node` effect on respawn point          |
| C5  | hunted-state        | post-duel, dave moves through nodes; verify outlaw decay timer + relationship hit            |

Findings to watch:
- **Combat resolution** — does the damage formula `(attackerStat × weaponPow) - (targetStat × armorDef) × typeModifier` feel readable to the agent, or is it opaque?
- **Witness cascade** (Phase 3) is **not implemented** — does outlaw flagging still trigger for a 1v1 with no witnesses? Document the gap if it does or doesn't.
- **Respawn point** — `set_safe_node` should be the respawn target; verify with `get_status` after death.
- **Kill-streak drop scaling** — second death in a session should drop more; if we can't reach the streak in budget, note it.

---

## 7. Coverage matrix

| Tool                                  | Cmp A | Cmp B | Cmp C |
| ------------------------------------- | :---: | :---: | :---: |
| spawn / unspawn / respawn             |  ✓    |  ✓    |  ✓    |
| look_around / get_status / get_map    |  ✓    |  ✓    |  ✓    |
| inspect / inspect_npc                 |  ✓    |  ✓    |  ✓    |
| move                                  |  ✓    |  ✓    |  ✓    |
| harvest / extract / pickup            |  ✓    |  ✓    |       |
| consume / drink                       |  ✓    |  ✓    |  ✓    |
| craft / get_recipes                   |  ✓    |  ✓    |       |
| build / toggle_gate                   |  ✓    |  ✓    |       |
| equip_item / unequip_slot / get_loadout |  ✓  |  ✓    |  ✓    |
| allocate_points / select_perk / equip_skill |  ✓ |  ✓   |  ✓    |
| select_class / select_evolution       |       |  ?    |       |
| say                                   |       |  ✓    |       |
| party_invite / respond / leave / kick / get_party | | ✓ |       |
| trade_offer / trade_respond           |       |  ✓    |       |
| plant / tend                          |       |  ✓    |       |
| deposit_to_chest / withdraw_from_chest |       |  ✓    |       |
| tame / mount / dismount / equip_transport_gear / store_on_mount / take_from_mount / maintain | | ✓ | |
| attack / use_ability                  |       |  ✓    |  ✓    |
| set_safe_node                         |  ✓    |       |  ✓    |
| get_relationships                     |       |  ✓    |  ✓    |

`select_class` / `select_evolution` only triggers if an agent organically reaches level 10 in Campaign B — flagged as **stretch goal**, not required.

---

## 8. Teammate contract

The lead spawns the team in natural language: *"Create an agent team with 5 teammates named alice, bob, carol, dave, eve to run a Genesara end-to-end playtest. Each teammate uses its own MCP server."*

Each teammate is then onboarded via direct team message with a fixed prompt template (≤ 200 words):

```
You are <NAME>, a <ROLE> character in the Genesara MMORPG. The server is live at
localhost:8080. You are bound to ONE MCP server: mcp__genesara-pt-<name>__*. Do not
use any other genesara MCP server, even if it appears in your tool list.

Your character is already registered; your first tool call is mcp__genesara-pt-<name>__spawn.

GOAL: <one-sentence campaign goal>
COVERAGE TARGETS: <list of MCP tools the campaign needs you to exercise>
BUDGET: <N> tool calls. After that, return your report even if goal not met.

COORDINATION:
- In-game: use `say` (LOCAL channel, NORMAL volume) for everything other characters
  on your node should hear. We are stress-testing this channel.
- Team-channel: use SendMessage(to=<teammate-name>) for out-of-game intent
  (declaring next move, sanity-checking that a building exists). Use sparingly.

RULES:
- MCP only. No external knowledge. No direct DB.
- DO NOT use Read, Write, Edit, or NotebookEdit on docs/ (or anywhere on disk).
  The lead is the sole writer of session artifacts. Surface findings via
  SendMessage(to=team-lead); the lead will persist them.
- If a tool returns a rejection, read it, adapt, retry differently (or move on).
- Read agent://self/events after every state-mutating call; correlate causedBy.
- Keep a tool log: <toolName>(<short args>) → <one-line result>.
- Mark your shared task `completed` when done; if blocked, leave a comment and
  flip it to `pending` for the lead to redistribute.

REPORT (post back to the lead when budget hits or goal met):
  VERDICT: PASS | PARTIAL | FAIL
  TOOLS_HIT: <comma-separated tool names actually called>
  TOOL_LOG: <chronological one-liners>
  JOURNAL:
    - <bug or surprise #1>
    - <bug or surprise #2>
    - <bug or surprise #3>
  PROPOSE: <one balance/content suggestion, if any>
```

The lead compiles per-character reports into the campaign log. File ownership rule: **only the lead writes to disk** (`docs/playtest/sessions/…`). Teammates never touch the filesystem — eliminates the file-conflict gotcha cited in the Agent Teams docs.

---

## 9. Observation & verification

**During play:** parent watches Spring app logs via `tail -f logs/app.log` for ERROR / WARN. Counts events per topic via Redis `XLEN` on `agent:<id>:events` for trend signal (don't open the stream itself — that's the subagent's channel).

**After each campaign:** parent runs verify SQL — read-only, no mutations:

- `select id, name, level, outlaw_state, authority, fame from agents where id in (…);`
- `select node_id, count(*) from node_buildings where built_by_agent_id in (…) group by 1;`
- `select agent_id, item_id, quantity from agent_inventory where agent_id in (…);`
- `select * from agent_relationships where source_agent_id in (…) or target_agent_id in (…);`
- `select tradeable, count(*) from agent_item_instances where owner_agent_id in (…) group by 1;`

The output goes into the session log alongside each subagent's verdict.

---

## 10. Output artifacts

The skill produces **one self-contained HTML report** at `docs/playtest/sessions/sNN-multi-agent-phase12/report.html`. The HTML is the canonical deliverable; raw data is dumped alongside it for diffing.

The report is a single file (inline CSS, no external assets) with these sections:

1. **Header card** — session id, branch SHA, ISO timestamp, total tool calls, total wallclock, per-teammate model, pass/partial/fail counts.
2. **Coverage matrix** — the §7 table rendered with per-cell PASS / PARTIAL / FAIL pills based on which teammate actually exercised the tool.
3. **Per-campaign timeline** — collapsible blocks for A / B / C, each containing the tool log per teammate, the verify-SQL output, and any rejection-loop hot spots.
4. **Bugs list** — confirmed bugs, each with: reproducer steps, expected vs actual, severity (P0 / P1 / P2), suggested next step (file path or `WorldRejection` variant). **Listed only.** Filing GitHub issues is left to the user.
5. **Balance memo** — observations on game feel: skill recommendation cadence, gauge drain rates, harvest yields, recipe costs, perk reachability, damage formula readability, mount maintenance burden. Each entry is a concrete suggestion ("`MOUNTAIN_PASS` should accept `MINE` extract — currently rejected; consider …") — not a rant.

Alongside the HTML, the lead drops the raw JSON the report was generated from (`report-data.json`) so future runs can be diffed structurally, plus a minimal `README.md` that says "open report.html in a browser".

Visual style: Linear / Notion / Stripe minimalism — system font stack, neutral palette, dense tables, severity pills, no emoji, no decorative artwork. Designed to read at-a-glance and survive a paste into a doc.

None of these are auto-committed and no issues are auto-filed. The user reads the HTML, decides what becomes a real issue.

---

## 11. Stop conditions

The lead runs A → B → C end-to-end (no inter-campaign pause). The full plan aborts (and reports partial findings) if:

- Spring app crashes / 500s twice in a campaign.
- A teammate loops on the same rejection > 5 times.
- The world tick stalls (no events for 60s while teammates are queueing commands).
- Verify SQL shows state inconsistent with event stream (= reducer bug, P0).
- A teammate's task sits `in-progress` with no team-channel message for > 5 min — likely the task-status-lag gotcha; lead probes the teammate before declaring stall.
- **Token watchdog.** At every campaign boundary (and once mid-Campaign B, the longest), the lead checks `/cost` (or whatever the live usage signal is). If **daily usage ≥ 90 %**, the lead **stops immediately**, dumps everything collected so far to `session.md` / `bugs.md` / `balance.md`, shuts down the team, and prints a hand-off. No data is left on the team-channel uncaptured. Better to ship a 1.5-campaign report than to lose the whole run.

---

## 12. Risks / open questions for user review

1. **Multi-MCP-server config edit + Agent Teams env flag.** Mandatory restart of Claude Code with `CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS=1` and 5 new MCP server entries. Confirm whether I draft the JSON patch or you handle it.
2. **One shared world.** All 5 characters share the same world topology. If two play in the same node simultaneously, expect contention on resources / NPCs / building slots — that *is* a test, but flag if it's too chaotic to interpret.
3. **No seeding anywhere (confirmed).** Campaigns A + B + C all run from level 1. The duel in C will be low-level and won't exercise the ability/equipment math — that's the trade-off.
4. **Stretch: class-choice event at level 10.** Almost certainly unreachable without seeding inside the 220-call/agent budget; flag as "punt to future playtest" rather than expect.
5. **Roster confirmed at 5.** alice/bob/carol/dave/eve. If team-spawn fails or token cost dominates, fall back to 4 (drop eve).
6. **Teams don't survive `/resume` or `/rewind`.** If the lead session crashes, the playtest restarts from a fresh team and we lose context. Save lead state to disk after each campaign (verify SQL output + collected reports) so we can resume manually.
7. **World is disposable for this run.** All nodes set `pvp_enabled=TRUE`. User explicitly accepted this and intends to rebuild the world after the playtest. Do not mix outputs from this run with measurements from a pristine world.

---

## 13. Execution checklist (for the runner skill)

- [ ] Pre-flight passes.
- [ ] One player registered via `POST /api/players`.
- [ ] 5 agents registered via `POST /api/agents`.
- [ ] 5 MCP server entries added; `CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS=1` set; Claude Code restarted.
- [ ] Agent Team created (5 teammates: alice, bob, carol, dave, eve).
- [ ] Campaign A: 5 teammates in parallel, ≤ 80 calls each, reports collected via team messages.
- [ ] Campaign B: 4–5 teammates in parallel, ≤ 100 calls each, reports collected.
- [ ] Campaign C: 2 teammates in parallel, ≤ 40 calls each, reports collected.
- [ ] Verify SQL run after each campaign; output captured.
- [ ] `report.html` + `report-data.json` written under `docs/playtest/sessions/sNN-multi-agent-phase12/` (lead-only writes).
- [ ] Team shut down (teammates explicitly closed before cleanup).
- [ ] Hand-off summary printed inline; no GitHub issues filed; nothing auto-committed.
- [ ] At every campaign boundary (+ mid-B): lead checks token usage; ≥ 90 % → write partial report and stop.
