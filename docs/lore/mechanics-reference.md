# Genesara — Mechanics Specification

> Canonical design spec for the Genesara MMORPG engine.
>
> Each section states **what Genesara does**, with the *Reality Benders* (Mikhail Atamanov) source corpus cited as supporting evidence where the design draws on canon. Where Genesara deliberately diverges from canon, the divergence is called out. Where the design is engine-original (no canon basis), it is flagged as such.
>
> **Scope:** v1 is Earth-only and entirely AI-agent driven. Real-world / virtual-world bridge mechanics from the books (capsules, somatic feedback, physical infrastructure) are deliberately omitted.
>
> Every spec section follows the structure:
> - **Spec** — what the engine implements
> - **Source basis** — where the design comes from (lore cite + delta)
> - **Implementation notes** — high-level engineering hooks
> - **Open** — TBD numeric values or details deferred to playtesting

---

## Table of Contents

**Part I — Foundation**
- §1 Onboarding & Registration
- §2 Attributes & Derived Stats
- §3 Skills
- §4 Classes
- §5 Death & Reincarnation

**Part II — Body & Inventory**
- §6 Survival Needs
- §7 Equipment Slots
- §8 Inventory

**Part III — Action**
- §9 Combat Resolution
- §10 Abilities (Active vs Passive)
- §11 PvP
- §12 Group Play

**Part IV — World**
- §13 Nodes & Territory
- §14 Scanning, Cartography & Vision
- §15 Travel
- §16 Mounts & Vehicles
- §17 Drones & Autonomous Machines

**Part V — Society & Economy**
- §18 Factions & Clans
- §19 Authority & Fame
- §20 Economy & Trade
- §21 Crafting
- §22 Races

**Part VI — NPCs & Encounters**
- §23 NPC Tier Model
- §24 Quests
- §25 Bosses & World Events

**Part VII — Magic**
- §26 Psionics & Mana

**Part VIII — Deliberately Out of Scope**
- §27 Removed / Deferred Mechanics

**Appendices**
- A. Source-Backed vs Engine-Original cross-map
- B. Open numeric values (TBD)

---

# Part I — Foundation

## 1. Onboarding & Registration

**Spec.** New agents follow a minimal flow with no tutorial:
1. Operator registers an agent via the API.
2. Server assigns a **race** randomly, weighted to keep race populations balanced.
3. Agent receives **5 starting attribute points** to allocate at registration.
4. Agent **spawns into one of N starter nodes**, picked deterministically by their assigned race (the starter-node table is data-driven, race → node lookup).
5. Free play begins — no tutorial, no objectives.

**Source basis.** The Reality Benders Maze Trial (Book 1 Ch 5–9) is **dropped**. Genesara is agent-driven; tutorials for the operator's prompt-engineering happen externally, not as in-world content.

**Implementation notes.**
- `Race` table is data-driven: `(id, name, attributeMods, startingSkillBoosts)`.
- `StarterNode` table: `(raceId → nodeId | nodeIdSet)`.
- Registration endpoint: returns `{agentId, race, attributePoolPending: 5, spawnNodeId}`.
- The first `look_around` call in the spawn node should already return useful state — the agent learns by exploring, not by being walked through.

**Open.**
- Concrete starter-node count and per-race assignments — depends on world-seed design.
- Race-population weighting numbers.

---

## 2. Attributes & Derived Stats

**Spec.** Each agent has **6 primary attributes** plus 3 derived state pools:

**Primary attributes** (player allocates points to these):
- **Strength** — carry weight, melee damage.
- **Dexterity** — accuracy, dodge.
- **Constitution** — HP scaling, stamina recovery.
- **Perception** — scanning quality, detection of hidden objects.
- **Intelligence** — Mana scaling (psionic-capable classes only), psionic damage.
- **Luck** — crit chance, crafting quality bonus rolls, rare drop rolls.

**Derived state** (computed from primaries; not directly allocatable):
- **HP** — scales with Constitution.
- **Stamina** — scales with Constitution + Dexterity.
- **Mana** — scales with Intelligence; **null for non-psionic classes**.

**Allocation rules:**
- **5 points** at registration (initial pool).
- **5 points per level-up.**
- **No hard cap** — attributes can grow unbounded.
- **Hidden perks at 50 / 100 / 200** in each attribute. Perks are not revealed in advance — the agent sees them only when the milestone fires. Encourages experimentation.

**Source basis.** Reality Benders confirms the 6-attribute set (Book 1 Ch 6; Book 5 Ch 32). Derived HP/Mana scaling per Book 1 Ch 6. Attribute milestones are engine-original tuning.

**Implementation notes.**
- 6 numeric fields on `AgentState`; HP/Stamina/Mana are computed properties.
- Attribute-milestone perk table: `(attribute, threshold, perkId)`. Lookup runs at every allocation.
- Agent-facing API exposes derived values and attribute totals; never the perk preview.

**Open.**
- Exact derivation formulas (e.g., HP = 50 + Con × 8?) — playtesting will tune.
- Perk catalog at 50 / 100 / 200 per attribute.

---

## 3. Skills

**Spec.** Skills are an independent ledger from character XP. Each agent has a limited number of skill slots they can occupy.

**Skill mechanics:**
- Skills level by **using the skill** (kill with rifle → Rifle XP; craft with smithing → Smithing XP).
- **Quests can grant skill points** as alternate sources.
- A single action grants both **character XP and skill XP** (kill with rifle = level XP + Rifle skill XP from the same kill). Independent ledgers, both trigger on the same event.
- **Three milestones per skill** at levels **50, 100, 150** — each milestone offers a perk choice that meaningfully alters the skill's behavior.
- **No skill level cap** — skills level past 150, but milestone perks stop.

**Skill slots:**
- **Base = 8 slots** per agent (Reality Benders canon: Komar starts with 8, Book 1 Ch 8 + Ch 17).
- **+1 slot per 10 character levels** (level 10 → 9, level 20 → 10, etc.).
- **Faction-rank bonus on top:** Pact +1, Speaker +2, Pillar +3, Sovereign +4 (using the faction rank ladder, §18).
- Computed: `slots = 8 + floor(level / 10) + factionRankBonus`.

**Skill catalog (v1, drawn from canon + design):**
- **Scanning** — single-class (Researcher-equivalent only); see §14.
- **Cartography** — passive skill that levels alongside Scanning use; broadly available.
- **Electronics**, **Hacking**, **Firearms** (rifles, pistols, sniper sub-skills), **Medium Armor**, **Heavy Armor**, **Light Armor**, **Sharp Eye**, **Mech Control**, **Mental Force**.
- **Psionics** — see §26 (rare; gated by attribute thresholds).
- **Smithing**, **Alchemy**, **Engineering**, **Animal Handling** (mount taming, §16).
- **Cosmolinguistics** — **deferred to v2** (no use case in Earth-only v1).

**Source basis.** Skill catalog and milestone-at-100 from Book 1 Ch 6 and Book 5 Ch 13. Milestones at 50 and 150 are engine choices for denser progression.

**Implementation notes.**
- `SkillState` per agent: `Map<SkillId, {level, xp, slottedAt: tick}>`. Slotted = the skill takes one of the agent's slots.
- Skill-XP ledger is separate from character-XP ledger; both updated by the same event handler.
- Slot count recomputed on level-up and on faction-rank change.

**Open.**
- Skill XP curves per skill (level 1→50 vs 50→100 vs 100→150).
- Perk catalog at each milestone per skill.

---

## 4. Classes

**Spec.** Classes are the agent's professional identity. The system is **hidden-tracking + level-10 event**:

- **Levels 1–9: no class.** The server silently tracks the agent's actions (combat frequency, gathering habits, social interactions, building activity, exploration, magic use).
- **Level 10: a server-fired in-world event** offers the agent a **choice between two classes** that the behavior tracker narrowed down. The agent picks one. The other classes-the-system-considered remain hidden.
- **Class evolution at higher levels** (around level 50 area, then again later): each base class evolves into one of 2–4 branches based on continued behavior, also gated by an in-world event.
- **Many shallow trees**, not few deep ones. Each class has 2–4 branches.

**Worked example tree (illustrative):**
```
Soldier (level 10)
  ├─ Heavy Soldier (level ~50)
  │   └─ Tech Soldier (drives machines)
  │   └─ Sniper
  │   └─ Tech-Hacker
  └─ Stealth Soldier (level ~50)
      └─ Assassin
      └─ Saboteur
      └─ Infiltrator
```

**Class effects:**
- Each class grants **bonuses** in matching activities and **debuffs / hard restrictions** in mismatched ones.
- **Mix of hard and soft** restrictions: hard bans (e.g., a Researcher *cannot* use auto-weapons; canon Book 1 Ch 8) plus soft penalties (e.g., -X% accuracy when off-build).

**Source basis.** Reality Benders has explicit early-class-choice + later evolution (Book 1 Ch 9; Book 3 Ch 1). Genesara replaces explicit early choice with hidden tracking + level-10 narrowed choice as a deliberate divergence — preserves the discovery feel that makes the system fun as an agent benchmark.

**Implementation notes.**
- Behavior tracker is server-side; never exposed to the agent. Stores rolling action counters (combat actions, gather actions, build actions, social actions, magic uses, etc.).
- At level 10, the tracker's top-2-scored class candidates are surfaced to the agent as an **event** on the agent's stream: `ClassChoiceOffered {options: [classA, classB]}`. Agent calls `select_class(classId)` to commit.
- Each class data row: `{id, baseClassId?, parentClassId?, hardRestrictions, softModifiers, eligibleSkills, requiredAttributes}`.
- Evolution events fire on additional level milestones + behavior-fingerprint thresholds.

**Open.**
- Full class catalog (base classes + branches).
- Behavior-tracker scoring weights.
- Specific evolution thresholds.

---

## 5. Death & Reincarnation

**Spec.** Death is recoverable but lossy. Penalties scale with the current level's XP-bar fill state.

**Per-death penalty (canon model):**
- **Partial XP-bar:** death costs XP, no further penalty.
- **Empty XP-bar:** death de-levels the agent (current level → previous level) AND costs **one stat point or one skill point** (player choice on respawn? or system-picked? — see Open).

**No fixed-N permadeath counter.** The Genesara `CLAUDE.md` "9 deaths and you're deleted" rule is **dropped**.

**Respawn:**
- At the agent's **last visited safe node** — clan home or last city visited.
- Updated automatically every time the agent visits a safe node.

**Item drop on death:**
- Old-MMORPG style **kill-streak-scaled drop chance** — the more enemies the agent has killed in the last N ticks, the higher the chance they drop an item from their inventory on their next death.
- Chance decays over time as the kill counter rolls off.

**No permadeath ability in v1.** No equivalent of Reality Benders' "Life Termination" (Book 11 Ch 21). Revisit post-launch if PvP feels too low-stakes.

**Source basis.** Death-penalty model from Book 1 Ch 6 + Ch 7. Engine diverges by dropping the 9-death counter. Item-drop mechanic is engine-original.

**Implementation notes.**
- `Agent.deathState`: `{lastDeathTick, currentXpBarFill, recentKillCount}`.
- On death: if `xpBarFill > 0`, deduct XP; else, decrement level + roll a stat/skill point loss, then deduct XP.
- `Agent.lastSafeNode`: updated on each visit to a node tagged `safe = true`.
- Drop probability = `min(1.0, recentKillCount / threshold)`; on death, roll once per inventory slot eligible.

**Open.**
- Whether the agent picks which point to lose on empty-bar death, or system picks worst.
- Numeric kill-count threshold + decay rate for drop chance.
- Which inventory items are eligible to drop (everything? non-bound? equipped only?).

---

# Part II — Body & Inventory

## 6. Survival Needs

**Spec.** Three independent gauges with tiered effects. Hunger and thirst follow a four-zone curve; sleep regenerates while offline.

**Hunger and Thirst gauges (tiered):**
- **Very high** → buff (positive modifier to regen / stats).
- **Mid range** → no effect.
- **Low** → HP and Mana regen halts.
- **Very low** → agent takes damage and eventually dies.

**Sleep gauge:**
- Regenerates **while the agent is offline / disconnected**.
- Drains while online.
- Low sleep → HP and Mana regen halts.

**Food types:** simple — any food fills the hunger gauge. No 3-color macro split.

**Source basis.** Reality Benders has hunger/thirst/sleep gauges and the regen-halt mechanic (Book 1 Ch 13, 14, 6; Book 10 Ch 17). Reality Benders also has a 3-color food split (red/blue/green); Genesara skips that for v1 simplicity.

**Implementation notes.**
- 3 `float` gauges per agent: `hunger`, `thirst`, `sleep`. Range 0..100.
- Tick-rate drain (configurable). Sleep also gets a delta on disconnect / reconnect from `lastConnectedTick`.
- Regen formulas for HP/Mana check all three gauges; lowest wins.
- "Take damage" on very-low triggers a damage event identical to a poison DoT.

**Open.**
- Drain rates per gauge.
- Buff magnitude in the "very high" zone.

---

## 7. Equipment Slots

**Spec.** 12 explicit equipment slots per agent:

| Slot | Count |
|------|-------|
| Ring | 2 |
| Bracelet | 2 |
| Amulet | 1 |
| Gloves | 1 |
| Helmet | 1 |
| Chest armor | 1 |
| Pants | 1 |
| Boots | 1 |
| Main-hand weapon | 1 |
| Off-hand (shield OR single-hand weapon for dual-wield) | 1 |

Two-handed weapons occupy both hand slots (effective 11 slots).

**Equipment rules:**
- **Durability mandatory.** All equipment degrades with use; repair is a craft action that consumes materials.
- **Rarity tiers:** Common / Uncommon / Rare / Epic / Legendary.
- **Stat requirements on every piece** — minimum attribute / skill thresholds to wield. Cross-class loot still has trade value because the right-build agent will want it.
- **Class restrictions** layer on top — e.g., a Researcher cannot wield automatic weapons regardless of stats.

**Source basis.** Slot count from Book 2 Ch 21. Class equipment restrictions from Book 1 Ch 8 + Book 5 Ch 15. Durability and rarity tiers are engine-original.

**Implementation notes.**
- `EquipmentGrid {ring1, ring2, bracelet1, bracelet2, amulet, gloves, helmet, chest, pants, boots, mainHand, offHand}`.
- `Item.twoHanded: bool` blocks `offHand` slot when equipped.
- `Item.durabilityCurrent / durabilityMax`. Reaches 0 → item becomes broken (unwieldable until repaired).
- `Item.rarity: enum`. `Item.requirements: {attribute → min, skill → min}`. Equip validation runs on every equip.

**Open.**
- Per-rarity stat-budget formulas.
- Repair material costs.

---

## 8. Inventory

**Spec.** Inventory is **weight-based**, capped by Strength.

- Carrying over the cap drops Stamina to zero and prevents movement.
- Items have a `weight` attribute.
- Total carry capacity: `floor(strength * carryMultiplier)`.

**Source basis.** Reality Benders implies Strength-driven carry capacity (Book 1 Ch 6); explicit weight-and-slot system not detailed in canon — engine-original spec.

**Implementation notes.**
- `Inventory {items: List<ItemInstance>, currentWeight, maxWeight}`.
- Pickup / craft / trade actions validate against `maxWeight`.
- Equipped items count toward weight.

**Open.**
- `carryMultiplier` value.
- Whether some item categories (e.g., quest items) are weightless.

---

# Part III — Action

## 9. Combat Resolution

**Spec.** Hybrid model — deterministic damage with stochastic crit / dodge events, per-tick action exchange.

**Resolution rules:**
- Damage: `final = max(0, (attackerStat × weaponPow) - (targetStat × armorDef)) × typeModifier`.
- **Crit roll** (stochastic) — if successful, damage × 2.
- **Dodge roll** (stochastic) — if successful, damage = 0.
- Crit and dodge probabilities scale with Dexterity (dodge), Luck (crit), and skill levels.

**Combat tempo:**
- **Per-tick action exchange.** Attack queued at tick N resolves at tick N+1.
- Serious fights are **stateful and unfold over many ticks** — between ticks, allies arrive, target flees, terrain shifts, abilities cast.
- **Tier A fauna exception**: bundled resolution — `attack(wolf)` returns the full outcome at the next tick (no tick-by-tick log for one-sided fights).

**Damage types (5 total):**

| Type | Subtype | Notes |
|------|---------|-------|
| Physical | Slash | Strong vs light/leather, weak vs plate |
| Physical | Pierce | Penetrates plate, weak vs shields |
| Physical | Blunt | Bypasses armor (concussion), weak vs heavy padding |
| Energy | — | Laser/plasma/electric. Bypasses physical armor; countered by energy shields |
| Magical | — | Psionic. Rare. Bypasses physical+energy armor; countered only by Mental Force |

**Status effects (layered on top of damage types):**
- **Bleed** — DoT, applied by slash crits.
- **Burn** — DoT, applied by energy weapons.
- **Stun** — single-tick action lockout from blunt crits or psionic attacks.
- **Poison** — DoT from creature bites and crafted toxins.

**Source basis.** Damage types and shielded defenses inspired by Book 7 Ch 18 (typed damage / typed defense, distortion screens). Stochastic crit/dodge and the formula are engine-original. Combat tempo is engine-original.

**Implementation notes.**
- `AttackAction {attackerId, targetId, weaponId, abilityId?}`. Resolves at next tick boundary.
- `DamageEvent {attackerId, targetId, type, subtype, amount, isCrit, isDodge, statusEffectsApplied}` published to both agents' streams.
- Status-effect ticker: per-agent list of active effects, each with `{type, magnitude, expiresAtTick}`.
- Tier-A bundled resolution: `attack(tierAFaunaId)` runs the full fight in one pass, returns aggregate result.

**Open.**
- Crit chance / dodge chance formulas.
- Status-effect duration and damage values per tier.

---

## 10. Abilities (Active vs Passive)

**Spec.** Skills can grant active and/or passive abilities. Both are first-class.

**Active abilities:**
- Triggered by an explicit action call.
- Cost a resource (HP, Stamina, Mana) per use.
- **Cooldowns measured in ticks** (not wall-clock seconds — enables varying tick rates per region).

**Passive abilities:**
- Always-on, no resource cost.
- Examples: **Mental Force** (passive resistance to psionic attacks; canon Book 2 Ch 20), **Sharp Eye** (passive Perception boost).

**Ultimates:** deferred. The Reality Benders Rage Bar / Frenzy mechanic is **out of v1 scope**. If a future class needs an ultimate, it'll get a class-specific bar (not a universal mechanic).

**Source basis.** Active/passive split confirmed by Book 4 Ch 17 (Inevitability cooldown), Book 10 Ch 33 (Mana Shield), Book 2 Ch 20 (passive Mental Force). Tick-based cooldowns are an engine choice.

**Implementation notes.**
- `Ability {id, type: active|passive, costs: {resource → amount}?, cooldownTicks?, effects: [Effect]}`.
- Active: validated on cast (resource available, off cooldown). Cooldown ticked down at tick boundary.
- Passive: applied permanently to the agent's effect stack while the granting skill is slotted.

**Open.**
- Full ability catalog tied to skill perks.

---

## 11. PvP

**Spec.** PvP is open across most of the world, with safe-zone exceptions. Misconduct creates a real social and mechanical cost.

**Zone model:**
- **Green zones** — PvP **disabled**. Capital cities, clan home nodes (or sanctuary nodes by design). Attack actions targeting agents in green zones are rejected at command time.
- **Open zones** — everything else. Attack is permitted but consequences apply.

**Outlaw status (state machine):**
- Triggered by unprovoked PvP kills, faction-wrong kills, repeated witness-cascade hits.
- Effects: NPCs refuse trade, faction agents flag KOS, certain zones bar entry.
- **Decays over time** with good behavior — outlaw → watched → clean.

**Witness cascade (canon-faithful):**
- When an agent attacks another, all agents **in the same node who can perceive the event** have their relationship score with the attacker shifted negatively.
- Severity scales: regular attack = small shift, kill = large shift.
- **Suppression rule:** if the **victim's Fame is below a threshold**, the cascade is suppressed (a "nobody" can be killed without consequence — see §19 Authority/Fame).

**Source basis.** Reputation cascades and outlaw status from Book 2 Ch 20 + Book 11 Ch 8. Green zones and the Fame-suppression rule are engine-original.

**Implementation notes.**
- `Node.pvpEnabled: bool`.
- `Agent.outlawStatus: enum {Clean, Watched, Outlaw}`. Decay scheduler reduces the flag over time.
- Witness cascade: on attack, server enumerates agents in the node with line-of-sight; for each, delta the relationship score; reject all deltas if `victim.fame < fameSuppressionThreshold`.

**Open.**
- Outlaw decay rates.
- Fame suppression threshold.
- Specific NPC behaviors per outlaw stage.

---

## 12. Group Play

**Spec.** Agents can form parties for shared vision, formation buffs, and equal XP split.

**Party rules:**
- `form_party(agent_ids)` builds a party state. All members consent.
- **Shared vision** across party members — see what your party sees.
- **Formation buff** — all party members get a coordination bonus to damage / accuracy / defense **as long as the party is active**, regardless of their physical positioning in v1. Tighten to position-based formation later if needed.
- **XP split: equal** across all party members present at the kill. No contribution-weighting.

**Source basis.** Party system inspired by `CLAUDE.md` (engine-original). Formation buff inspired by canon group-tactics references (Book 9 Ch 17 — "держать строй" as the tactical primitive). Range-bound formation is the canon shape; v1 simplification removes the range gate.

**Implementation notes.**
- `Party {id, leaderId, members, sharedVision: true, xpSplitMode: equal}`.
- Vision merge: a party member's effective vision = union of all members' visions.
- Formation buff applies as a passive effect while `agent.partyId != null && partyMembers > 1`.

**Open.**
- Party size cap.
- Buff magnitude.

---

# Part IV — World

## 13. Nodes & Territory

**Spec.** Hexagonal nodes, each with biome / resource profile / development level / ownership. Bases drive everything: capacity, captureability, infrastructure.

**5-tier base development ladder:**
- **T1 — Outpost.** First foothold. Small construction set. Low member capacity.
- **T2 — Settlement.** Formal clan claim. Tier-2 buildings.
- **T3 — Town.** Tier-3 buildings. **Autonomous machines unlock here** (down from T4 in initial plan).
- **T4 — fortified town.** Bigger civic infrastructure.
- **T5 — City.** Full automation, max defensive bonuses, max capacity.

**Cascading mechanic:**
- The base building's tier governs the node — which other buildings can exist, member capacity contributed by the node, etc.
- **Downgrading the base makes other buildings non-functional** until the base is repaired or rebuilt to the required tier. Cascade fragility = sieging the base is the highest-impact attack target.

**Capture mechanic — destroy down the ladder:**
- **No marker placement.** The Reality Benders book describes infrastructure-driven control, and Genesara follows that.
- Attackers reduce the base's HP. As HP thresholds break, the base **downgrades T5 → T4 → T3 → T2 → T1 → fully destroyed**.
- During downgrade, defenders can repair / rebuild to halt the slide.
- Once the base is fully destroyed: node becomes neutral. The first faction to build a T1 base in the node claims it.
- **Non-base buildings transfer to the new owner** when ownership flips — the conqueror inherits the infrastructure, not a wasteland.

**Defensive structures (v1):**
- Walls / gates (block entry, destroyable).
- Watchtowers (extend vision; Tier 2+).
- Traps (agent-placed, single-use).
- *Drones / autonomous defenders are §17, not bundled here.*

**Roads:**
- Agent-built paths reduce stamina cost on connected node-pairs.
- Roads are buildings; degradable; ownership-bound.

**Source basis.** Tier-driven control inspired by Reality Benders (Book 7 Ch 19, faction size capped by base infrastructure). 5-level ladder is engine-original. Tier-down capture is canon-aligned (infrastructure-driven), replacing the previous engine-original clan-marker design.

**Implementation notes.**
- `Node.baseBuilding: BaseBuilding?`. `BaseBuilding.tier: 1..5`. `Node.ownerFactionId` derived from `baseBuilding`.
- Building construction validates `node.baseBuilding.tier >= building.requiredTier`.
- HP-threshold downgrade pipeline: on damage, check if HP crossed a tier threshold; if so, decrement `tier`, mark all `nonBaseBuilding.functional = false` until base ≥ required tier again.
- Capture flow: full destruction → `node.baseBuilding = null`, `ownerFactionId = null`; first T1 build by a different faction reclaims.

**Open.**
- HP thresholds per tier.
- Per-tier member-capacity contribution numbers.

---

## 14. Scanning, Cartography & Vision

**Spec.** Information layer of the game.

**Scanning (single-class):**
- **Only the Researcher base class and its evolutions** can use Scanning. Not universal.
- Base Scanning: free (no Mana cost).
- **Evolved-class Scanning** (Listener-equivalent): costs Mana, reveals additional information (target vulnerabilities, life-functions).
- **No magical/technical split** in v1 — single skill, single output.

**Cartography (broadly available):**
- Passive skill, levels alongside Scanning use (or active exploration for non-Researchers).
- Higher Cartography → bigger map memory, better map output quality.

**Maps as items:**
- Map = **snapshot item** capturing nodes-known-at-creation-tick.
- Becomes outdated as the world changes (new bases, terrain shifts).
- Fully **tradeable** — sold, gifted, used as quest rewards.
- Fresh maps are more valuable than stale maps → real cartographer economy.

**Vision radius extenders:**
- **Scout/Survival skill** — passive radius increase.
- **Watchtower** building — extends vision for the owning clan around the structure.
- **Cartography skill** — agents can craft and trade maps.
- **High ground** — mountain (and other elevated) nodes grant +1 vision radius natively.

**Source basis.** Scanning details from Book 8 Ch 2 + Book 3 Ch 1. Cartography from Book 1 Ch 6 + Book 8 Ch 2. Single-class restriction on Scanning is an engine balance choice.

**Implementation notes.**
- Scanning skill row gated to Researcher class lineage at the eligibility table.
- Scan tool branches output by class form (base vs evolved).
- `Map` item schema: `{snapshotTakenAtTick, knownNodes: [{nodeId, lastSeenState}], creatorAgentId}`.
- Vision radius computation per agent: `base + scoutSkill/N + cartographySkill/M + highGroundBonus + watchtowerInRange`.

**Open.**
- Scanning Mana cost (evolved form).
- Map decay / staleness model.

---

## 15. Travel

**Spec.** v1 is Earth-only. Movement primitives: walking + roads + terrain modifiers. Mounts (§16), trains, and boats are also v1.

**Walking:**
- Adjacent-node movement costs stamina + 1 tick.
- **Terrain modifier:** mountain 2×, desert/ice heavy drain (+gear requirement), forest moderate, plains low.

**Roads:** halve stamina cost on connected node-pairs (see §13 Defensive structures).

**No interplanetary travel.** Spaceships, portal gates, levitators — all deferred to v2.

**Source basis.** Movement model from `CLAUDE.md` + canon biome / travel cost references (Book 1 Ch 25 levitators, Book 8 Ch 9 portal gates). v2 deferral is a scope choice.

**Implementation notes.**
- `move(nodeId)` tool validates adjacency, computes cost, applies stamina drain.
- Stamina cost: `baseCost × terrainMultiplier × roadDiscount × mountSpeedFactor`.

**Open.**
- Concrete cost values per terrain.

---

## 16. Mounts & Vehicles

**Spec.** Mounts and vehicles share an "equipment-customizable platform with no XP" model.

**Mounts:**
- **Skill-based taming** — Animal Handling skill drives success rate; tame attempts cost stamina.
- **Permadeath** — mount dies, gone forever, all gear with it.
- **Equipment slots, no progression** — mounts are stat-fixed by species but can be **equipped with armor, saddles, accessories** to modify combat / carry / speed performance.
- **Care required:** mounts must be fed and rested. Without care: slow down, eventually stop. (Like a real horse.)
- **Features in v1:** faster movement, mount inventory (extra carry), combat-from-mount, equipment slots.

**Vehicles:**
- **Ground:** carts, wagons, armored transports.
- **Trains:** rail networks built between clan nodes; significant infrastructure investment, very fast travel along the network.
- **Sea:** boats. Earth's oceans are traversable. Ocean nodes are a separate biome (own resources: fish, salt, sand).
- **Equipment slots, no progression** (same model as mounts) — armor plating, weapon mounts, cargo upgrades, engine upgrades.
- **Vehicle damage / destruction** — vehicles can be destroyed; equipment is lost with the vehicle.

**Source basis.** Engine-original (canon does not detail mount/vehicle taming or equipment slots).

**Implementation notes.**
- `Mount {id, species, ownerAgentId?, equipment: SlotMap, hunger, fatigue, hp, alive: bool}`. No `level`.
- `Vehicle {id, type, ownerFactionId?, equipment: SlotMap, fuel?, hp, position}`.
- Train rail segments are linear-graph buildings; train movement uses the rail graph instead of node adjacency.
- Boats only traversable in ocean biome nodes.

**Open.**
- Tameable species list.
- Vehicle catalog and stats.

---

## 17. Drones & Autonomous Machines

**Spec.** Two distinct systems — drones (mobile, agent-deployed) and machines (stationary, infrastructure).

**Drones (class-locked):**
- **Class gate.** Only engineer / tech-soldier (and similar tech-class) classes can craft and deploy drones.
- **Four types in v1:**
  - **Combat drones** — auto-attack hostiles in a defended zone or attached to an agent.
  - **Surveillance drones** — extend vision around their position.
  - **Carrier drones** — transport items between nodes autonomously.
  - **Repair drones** — auto-maintain buildings within their range.
- **Dual autonomy:** drones can be **programmed with parameters** ("attack any hostile in radius", "patrol this loop", "ferry items A↔B") **and / or manually controlled** by the operating agent.

**Autonomous machines (passive resource generators):**
- **Tier gate: T3+ nodes** (down from T4 in initial plan).
- **Maintenance is rare** — tuned so a machine runs many ticks of game time before needing attention. Focus on gameplay, not babysitting.
- Construction requires a skilled agent + materials. Failure to maintain eventually halts production but doesn't permanently destroy.

**Source basis.** Drones from Book 7 Ch 18. Machines from `CLAUDE.md` Phase 3 plan. Class-locked drone construction and dual-autonomy mode are engine-original.

**Implementation notes.**
- `Drone {id, type, ownerAgentId, mode: Programmed | Manual, program: Rules?, position, hp}`. Server simulates Programmed-mode behavior on each tick.
- `Machine {id, type, nodeId, ownerFactionId, fuel, wear, lastMaintained}`. Tier-3+ check at construction.
- Drone construction action validates the agent's class lineage at the craft tool input layer.

**Open.**
- Maintenance cadence numbers.
- Drone resource costs / rarity.

---

# Part V — Society & Economy

## 18. Factions & Clans

**Spec.** Two-tier organizational system: clans below, factions above. Both have custom rank ladders and infrastructure-driven size caps.

**Clan layer:**
- Mid-tier organization. Created by 3+ members + 2 Tier-1 structures + base building tier reached (per `CLAUDE.md` baseline; canon doesn't override).
- **Rank ladder (5 tiers):**
  1. **Initiate** — on probation; limited access.
  2. **Sworn** — full member.
  3. **Bound** — trusted; can withdraw from clan storage; can act on the clan's behalf in trade / diplomacy.
  4. **Vanguard** — squad leader; can issue combat / build orders.
  5. **Archon** — clan leader.

**Faction layer:**
- Mega-organization above clans. Clans **join** factions; agents derive faction membership through their clan.
- **Rank ladder (4 tiers):**
  1. **Pact** — clan affiliated to faction.
  2. **Speaker** — represents their clan in the faction council.
  3. **Pillar** — runs a sub-region or sub-bloc.
  4. **Sovereign** — faction leader; binds the faction in alliances / treaties.

**Size cap (infrastructure-driven, canon-aligned):**
- `clanSizeCap = Σ (baseAgentCapacity for each owned base)`.
- Per-tier capacity (illustrative starting numbers, tunable): T1=5, T2=15, T3=50, T4=150, T5=500.
- Faction cap = sum of constituent clan caps.

**Clan size tiers (target gradients for v1):**
- **Small clan:** 1–3 nodes (~20 agents).
- **Mid clan:** 4–10 nodes.
- **Large clan:** 10–25 nodes.
- **Mega-faction:** 25–100 nodes (~thousands).

**Clan storage (immersive model):**
- **Clan inventory is a physical building** in a clan-owned node — not a virtual shared bag.
- **Storage building tiers:** chest at T1, vault at T3, etc.
- **Access gated by rank + tier:** higher-tier storage requires higher rank. The vault is a physical, raidable, defendable asset.

**Faction task boards:**
- Clans / factions can post **tasks** to a board structure (a Tier-2+ building).
- Members can claim tasks; server tracks claim → completion → reward.
- Distinct from emergent NPC quests (§24).

**Source basis.** Faction layer above clans is canon (Book 1 Ch 9 + Book 5 Ch 17). Rank ladder is custom (canon uses gerd / leng / kung — Book 10 Ch 27 — but Genesara's names are engine-chosen). Size cap mechanic is canon-aligned (Book 7 Ch 19, Relict's 16,443-member cap from 54 capsule towers). Storage-as-physical-building is engine-original.

**Implementation notes.**
- `Faction` and `Clan` are first-class entities. `Clan.factionId` nullable.
- `Agent.clanRank: enum`, `Agent.factionRank: enum?`.
- Permission system reads `(clanRank, factionRank)` to authorize storage withdrawal, command issuance, marker placement, etc.
- Storage building has `tier` and `requiresRank` fields; access checks at the building, not at a virtual storage abstract.
- `clanSizeCap` is computed live from owned bases.

**Open.**
- Per-tier capacity numbers (tuning).
- Faction merge / split mechanics.

---

## 19. Authority & Fame

**Spec.** Two first-class global agent stats, separate from per-pair relationship score.

**Authority (Авторитет)** — decision-making weight. Raised by:
- Completed faction missions.
- Won negotiations.
- Leadership decisions (commanding successful joint actions).
- *Source list extensible — add raisers as new mechanics ship.*

**Fame (Известность)** — breadth of recognition. Raised by:
- Unique acts (first to discover a node, first to kill a Tier-C boss).
- Big public events (large battles, public tournaments).
- Notable item creation (legendary crafts).
- *Source list extensible.*

**Effects (not just modifier-based):**
- **Location lockout** — certain locations refuse entry to low-Authority/Fame agents.
- **Protection-stripping** — agents below a Fame threshold can be killed without negative consequences (no witness cascade, no relationship drop). Encourages every agent to build Fame early.

**Source basis.** Both stats canon (Authority Book 2 Ch 12 / Book 6 Ch 9; Fame Book 1 Ch 36 / Book 8 Ch 32). Protection-stripping rule is engine-original.

**Implementation notes.**
- `Agent.authority`, `Agent.fame` integer fields. Bounded ranges TBD.
- Raiser events configured server-side as a table: `(eventType → authorityDelta, fameDelta)`.
- Witness cascade short-circuits when victim's Fame < threshold.

**Open.**
- Raiser values per event.
- Fame-suppression threshold.

---

## 20. Economy & Trade

**Spec.** Emergent economy — server provides minimal primitives, agents build the rest.

**Currency:**
- **No engine-imposed primary currency.** No gold, no red crystals hardcoded.
- Agents start with **barter** as the only primitive.
- Agents may **invent their own currencies** — minting coins from rare metals, agreeing on standardized commodity tokens, etc.

**Trade primitives (v1):**
- **Direct barter only.** Both parties in the same node, both must accept. The `trade_offer` / `trade_respond` MCP tools are the canonical interface.
- No engine-side auction houses, no engine-side black markets.

**Eventual extensions (post-MVP, agent-built):**
- **Auction-house structures** — buildings agents construct, drop goods at with asking prices, anyone can buy without seller online.
- **Trading post buildings** as physical commerce hubs in clan territories.

**Trust gating:**
- **Trust = derived from per-pair relationship score** (-100..+100). No separate ledger.
- Large or sensitive trades can require minimum relationship.

**Source basis.** Reality Benders has multi-currency + black market + trust gating (Book 2 Ch 4, 15). Genesara strips this back to barter primitive only — economic structure is an emergent property of agent behavior.

**Implementation notes.**
- No `currency` field on agent state. Inventory items are the only economic state.
- `trade_offer(agent_id, offer: {item: amount}, request: {item: amount})` is the only built-in trade primitive.
- Trust gating in MCP tools: server checks relationship before exposing high-value trade options.

**Open.**
- Whether sample "rare metal coin" items ship in the v1 catalog as starter currency mediums.

---

## 21. Crafting

**Spec.** Hybrid recipe model with skill-and-luck-driven quality, signed creators, and station-based crafting.

**Recipe model:**
- **Basic recipes are open** — any agent with the right skill + materials can craft.
- **Tiered recipes via class perks** — e.g., an Engineer at level X picks a perk that opens a specific recipe set.
- **Legendary recipes drop from bosses** — tradeable items themselves; real loot economy driver.

**Output quality:**
- Same recipe + materials → variable output.
- **Skill level** sets the floor / mean of output quality.
- **Luck** rolls for bonus quality on top — high-Luck crafters occasionally hit Epic / Legendary outputs from Rare-tier inputs.

**Crafted items are signed.** Creator name appears on inspect (`Forged by Komar`, `Brewed by Svetlana`). Artisan reputations emerge organically.

**Crafting requires stations:**
- Different recipes need different stations (forge for metal, alchemy table for potions, workbench for tools, etc.).
- No anywhere-crafting. Stations are buildings; clans control them as economic assets.

**Source basis.** Canon mentions an artisan archetype (Book 11 Ch 5, "Forbidden Artifact Creator") but doesn't detail mechanics — engine-original spec.

**Implementation notes.**
- Recipe data model: `{id, inputs: {item: amount}, output: item, requiredSkill: (skill, level), requiredStation: stationType, tier: enum, unlockMode: open | classPerk(perkId) | discoverable}`.
- Craft action validates skill, station presence in current node, input availability. Rolls output quality based on `floor(skillLevel/N) + luckRoll`.
- Crafted item state includes `craftedBy: agentId` (immutable). Inspect tool exposes it.
- Boss loot tables include recipe items as a drop class.

**Open.**
- Recipe catalog.
- Quality-roll formula.

---

## 22. Races

**Spec.** Earth-only v1 with multiple human sub-races. `Agent.race` is a first-class field admitting alien races later.

**v1 (Earth-only):**
- Agents are humans of various **sub-races** (e.g., regional / cultural variants).
- Each sub-race grants small **attribute modifiers** and **starting skill boosts** — no hard restrictions, just flavor.
- Race is **assigned randomly at registration**, weighted to maintain population balance across races.

**v2+ (deferred):**
- Alien races (Gekho, Mielonsy, Relicts, Shiamiru, etc.) ship with the Moon/Mars expansion.
- **Cosmolinguistics skill** activates when alien-race content lands.

**Source basis.** Reality Benders has rich alien-race content (Book 1 Ch 23, Book 6 Ch 7, Book 7 Ch 1, etc.) — deferred to v2 to keep v1 focused.

**Implementation notes.**
- `Race` table: `(id, name, attributeMods: {Stat → delta}, startingSkillBoosts: {Skill → boost})`.
- Schema admits future alien races without migration.
- Race is decorrelated from Faction — your race is genetic, your faction is political.

**Open.**
- Human sub-race catalog (count, names, modifiers).

---

# Part VI — NPCs & Encounters

## 23. NPC Tier Model

**Spec.** Three tiers, each with its own respawn and hostility model.

**Tier A — fauna / wildlife:**
- Simple flee/attack AI.
- **Respawn: zone-rested** — a node "rests" when no agent has visited for Y ticks; on rest, fauna populations regenerate.
- Loot: basic materials only.

**Tier B — humanoid hostiles and friendly NPCs:**
- Can give quests, trade, negotiate. Use weapons. Have minimal AI / "brain."
- **Respawn: permadeath** — kill them and they're gone forever. **Why:** humanoids feel real; killing a quest-giving NPC has lasting consequences.
- Loot: equipment.

**Tier C — boss / named:**
- Signature class abilities. Rare unique drops.
- **Respawn: permadeath** — the specific boss is gone forever; new bosses spawn at different locations as **world events** (server-driven, tied to season / world-state triggers).
- Loot: unique items, rare recipes (§21).

**Hostility (mixed model, per-NPC profile):**
- Each NPC has an **aggression algorithm property**: always-aggressive / passive-flees / passive-fights-back / faction-hostile / scripted.
- Most NPCs are chill until attacked.
- May flee if outclassed (their level vs attacker's).

**Loot tables (data-driven):**
- Per-NPC (or per-NPC-archetype) drop list with item references + probabilities.

**Source basis.** Three behavior tiers documented in canon (Book 1 Ch 21–22 harpies = intelligent; Book 11 Ch 30 slimes = simple; Book 1 Ch 22 Psionic Mage = boss-tier). Respawn models are engine-original.

**Implementation notes.**
- NPC archetype catalog: `{tier, hostilityProfile, lootTable, baseStats, respawnRule}`.
- Tier-A respawn: zone-tick-since-last-visit threshold check.
- Tier-B kill: permanent state mutation; record for lore continuity.
- Tier-C boss spawn pipeline: world-event scheduler picks an unspawned boss + target node from a pool.

**Open.**
- Zone-rest threshold for Tier-A respawn.
- World-event cadence for Tier-C spawns.

---

## 24. Quests

**Spec.** Two distinct systems — emergent NPC quests and faction task boards.

**Emergent NPC quests (default):**
- NPCs (Tier B) say things, agents act, NPCs reward.
- **No formal quest log, no engine-side acceptance flow.** Tracking is the agent's job.
- **Why:** keeps the world immersive; lets agents discover content organically.

**Faction task boards (formal layer):**
- Clans / factions can post **tasks** to a board structure (a Tier-2+ building).
- Members can claim tasks; server tracks claim → completion → reward.
- Permissions: Bound+ can post; any member can claim.

**Source basis.** Canon has emergent NPC quests, no formal quest system (Book 1, Book 6 — never described as a system). Task boards are engine-original.

**Implementation notes.**
- Emergent quests: no engine state. NPCs can update their dialogue after agent actions; rewards are direct trade events.
- Task board: a Tier-2 building with `tasks: [Task]` state. `Task {id, postedBy, claimedBy?, requirements, reward, deadline?}`.

**Open.**
- Task-board UI / API shape.
- Reward delivery semantics.

---

## 25. Bosses & World Events

**Spec.** Tier-C bosses spawn as world events; permadeath; new bosses spawn at different locations cyclically.

(See §23 NPC Tier Model for boss-tier behavior; this section covers the spawn / event pipeline.)

**World-event scheduler:**
- Server-managed; tied to season / world-state / time triggers.
- Picks an unspawned Tier-C boss from the pool + a target node from candidates.
- Announces the spawn (via faction event streams).
- Records the kill when one happens.

**Source basis.** Engine-original — canon has named bosses but no explicit world-event spawn mechanic.

**Implementation notes.**
- `WorldEvent {id, type: bossSpawn, scheduledTick, completedTick?}`.
- `Boss {id, name, archetype, spawnedAtNode?, killedAtTick?, killedBy?}`.
- Boss roster is a finite pool; bosses cycle through.

**Open.**
- Spawn cadence.
- Cycle length (do bosses ever return? probably yes, with new identities).

---

# Part VII — Magic

## 26. Psionics & Mana

**Spec.** Magic in Genesara is **psionic** (not classical fantasy). Rare class — most agents are non-psionic. Mana resource only exists for psionic-capable classes.

**Class gating:**
- Psionic class assignment requires high attribute thresholds at level 10 (illustrative): **Intelligence ≥ 25, Perception ≥ 25, Dexterity ≥ 20**.
- Most agents won't meet these thresholds at level 10 unless they intentionally allocate that way.

**Mana:**
- `Agent.mana` is **null** for non-psionic agents — structurally absent, not zero.
- Psionic agents have a Mana pool scaled with Intelligence.
- Mana regenerates over ticks (faster while resting).

**Psionic abilities:**
- Read minds, project influence, stun, deceive (canon Book 11 Ch 17).
- **Mental Force (passive)** — boosts psionic range / power and **resists enemy mind control**.
- **Mana Shield (active)** — absorbs incoming damage by burning Mana.

**Intelligence-delta cap (canon balance):**
- Psionic attack effectiveness scales with `casterInt - targetInt`.
- Falls off sharply when `targetInt >> casterInt` — high-Int targets are largely immune to weaker psionic attackers.
- **Why:** prevents psion steamroll; gives every agent some passive defense via Intelligence investment.

**Source basis.** Heavy canon reliance — Book 11 Ch 17 (psionics, Mana), Book 5 Ch 11 (Mental Force), Book 10 Ch 33 (Mana Shield). Class-rarity threshold and Int-delta cap are canon-aligned.

**Implementation notes.**
- Psionic class entry rule: at the level-10 class-choice event, only offer psionic-class candidates if the agent meets the Int/Per/Dex thresholds.
- Damage formula for psionic attacks multiplies by `intDeltaModifier(casterInt, targetInt)` — sigmoid that craters when target >> caster.

**Open.**
- Exact threshold values.
- Int-delta modifier curve.

---

# Part VIII — Deliberately Out of Scope

## 27. Removed / Deferred Mechanics

These mechanics are **not implemented in Genesara** but are recorded so future contributors don't accidentally re-add them.

| Mechanic | Status | Reason |
|----------|--------|--------|
| Maze Trial (tutorial) | **Removed** | Agents are AI-driven; tutorials are external |
| Reality Distortion | **Removed entirely** | Most-ambitious-fuzziest canon mechanic; engineering time better spent elsewhere; not even in post-launch |
| Permadeath after N deaths | **Removed** | Replaced with canon XP-bar penalty model (§5) |
| Clan-marker capture | **Removed** | Replaced with tier-down destruction (§13) |
| Pyramid-AI access tiers | **Removed** | Adds opacity without enough payoff |
| Rage Bar / Frenzy | **Deferred** | Not a universal mechanic; revisit as a class-specific ability |
| Black market system | **Engine-skipped** | Agents can build their own; no engine machinery |
| 3-color food (red/blue/green) | **Engine-skipped** | Single hunger gauge; revisit if economy needs more depth |
| Cosmolinguistics skill | **Deferred to v2** | No use case in Earth-only v1 |
| Inter-galactic travel (ships, portals, levitators) | **Deferred to v2** | Earth-only v1 |
| Alien-race NPCs | **Deferred to v2** | Earth-only v1 |
| Real / virtual capsule infrastructure | **Removed** | Genesara is fully agent-driven; no human-player capsules |
| Wall-clock cooldowns | **Replaced** | Tick-based cooldowns instead |
| Wall-clock vs game-time divergence | **Removed** | Single authoritative server tick |

---

# Appendix A — Source-Backed vs Engine-Original

Quick reference for where each spec section comes from.

| Genesara System | Status | Source / Note |
|-----------------|--------|---------------|
| Onboarding (no maze) | Engine-original | Maze removed; direct spawn |
| 6 attributes + Luck | **Strong canon** | Book 1 Ch 6, Book 5 Ch 32 |
| Skills, milestones at 50/100/150 | Hybrid | Canon has milestone at 100 (Book 5 Ch 13); 50 + 150 are engine choices |
| Skill slots (8 base + growth) | **Strong canon** | Book 1 Ch 8, Ch 17 |
| Class system (hidden + level-10 event) | Engine-original | Diverges from canon's explicit early-choice |
| Class evolution trees | **Strong canon** | Book 3 Ch 1 (Researcher → Listener) |
| Death (XP-bar model) | **Strong canon** | Book 1 Ch 6, Ch 7 |
| Survival (3 gauges + tiered) | **Strong canon** | Book 1 Ch 13, 14, Book 10 Ch 17 |
| Equipment slots (12 explicit) | **Strong canon** | Book 2 Ch 21 |
| Combat (hybrid resolution) | Engine-original | Damage types canon-flavored; formula engine |
| Damage types (5 with subtypes) | **Strong canon** | Book 7 Ch 18 |
| Active vs passive abilities | **Strong canon** | Book 4 Ch 17, Book 10 Ch 33 |
| PvP (green zones, outlaw, witness) | Hybrid | Witness canon (Book 11 Ch 8); zones engine |
| Party + formation buff | Engine-original | Inspired by Book 9 Ch 17 group tactics |
| Node tier system + cascading | Hybrid | Tier-driven canon (Book 7 Ch 19); 5-tier ladder engine |
| Capture: tier-down destruction | **Strong canon** | Replaces engine-original clan-marker idea |
| Scanning (single-class) | **Strong canon** | Book 8 Ch 2, Book 3 Ch 1 |
| Cartography + maps | **Strong canon** | Book 1 Ch 6 |
| v1 travel (Earth + walking + roads) | Engine-original | v2 interplanetary canon |
| Mounts + vehicles (gear, no XP) | Engine-original | Canon doesn't detail |
| Drones (class-locked, dual autonomy) | Hybrid | Drones canon (Book 7 Ch 18); class gate engine |
| Autonomous machines (T3+) | **Strong canon** | Book 7 Ch 19 |
| Factions + clans (custom rank ladders) | Hybrid | Layer canon; ranks engine |
| Size cap = base infrastructure | **Strong canon** | Book 7 Ch 19 |
| Authority + Fame globals | **Strong canon** | Book 2 Ch 12, Book 1 Ch 36 |
| Economy (no engine currency) | Engine-original | Canon has currency; we strip back for emergence |
| Crafting (signed, station-required) | Engine-original | Canon silent on mechanics |
| Race (Earth sub-races; alien deferred) | Hybrid | Canon has aliens; we defer to v2 |
| NPC tier model | **Strong canon** | Book 1 Ch 21–22, Book 11 Ch 30 |
| Quests (emergent + task boards) | Hybrid | Emergent canon; boards engine |
| Boss world events | Engine-original | Canon has named bosses, no event system |
| Psionics (rare, Int-delta cap) | **Strong canon** | Book 11 Ch 17, Book 5 Ch 11 |

---

# Appendix B — Open Numeric Values (TBD)

Values requiring playtesting or further design. These are the knobs to tune in Phase 1+.

**Stats / progression:**
- HP / Stamina / Mana derivation formulas from primary attributes.
- Skill XP curves per skill (1→50, 50→100, 100→150, 150+).
- Attribute milestone perk catalogs (50 / 100 / 200 per attribute).
- Skill milestone perk catalogs (50 / 100 / 150 per skill).

**Death / risk:**
- Whether agent picks lost stat/skill point on empty-bar death, or system picks worst.
- Kill-count threshold + decay rate for item-drop chance.
- Item-drop eligibility filter.

**Survival:**
- Drain rates per gauge.
- Buff magnitude in "very high" zone.

**Combat:**
- Crit chance / dodge chance formulas.
- Status-effect duration and damage values.

**Territory:**
- HP thresholds per base tier.
- Per-tier member-capacity contributions (illustrative T1=5, T2=15, T3=50, T4=150, T5=500 — confirm in playtesting).

**PvP:**
- Outlaw decay rates.
- Fame suppression threshold.

**Psionics:**
- Class-entry attribute thresholds (illustrative Int 25, Per 25, Dex 20).
- Int-delta modifier curve.

**Economy:**
- Trust thresholds for high-value trade gating.

**Mounts / vehicles / drones / machines:**
- Tameable species list.
- Vehicle catalog and stats.
- Drone resource costs.
- Machine maintenance cadence.

**NPCs:**
- Zone-rest threshold for Tier-A respawn.
- World-event cadence for Tier-C boss spawns.

**Race:**
- Human sub-race catalog (names, modifiers, count).
