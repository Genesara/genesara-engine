# Genesara Engine — Domain Context

A glossary of domain terms whose meaning a future contributor (or an AI agent reading the code) cannot derive from the code alone. Code-level structure is documented in `docs/architecture.md`; lore-canon is in `docs/lore/mechanics-reference.md`. This file is the **short, semantic** index — what each term means in this engine, not how it's wired.

## Equipment, slots, bonuses

- **Equipment slot** — one of the 12 canonical body-positioned slots (`HELMET`, `CHEST`, `PANTS`, `BOOTS`, `GLOVES`, `MAIN_HAND`, `OFF_HAND`, `RING_LEFT`, `RING_RIGHT`, `BRACELET_LEFT`, `BRACELET_RIGHT`, `AMULET`). Two-handed weapons occupy MAIN_HAND and block OFF_HAND.
- **`valid-slots`** — declared per equipment item, the set of slots the item may occupy. A ring lists both `RING_LEFT` and `RING_RIGHT`; the equip reducer picks the empty one.
- **Equipment instance** — a per-agent row tracking a specific crafted/looted piece. Distinct from the catalog `Item`: instance carries the rolled rarity, current durability, creator signature.
- **`bonuses:` field** — single heterogeneous list on equipment items. Each entry is `{ target: <enum>, magnitude: Int }` where `target` is one of `DamageType` (→ armor defense), `Attribute` (→ attribute bonus), or `ScalingEffect` (→ passive buff). Dispatched into typed sealed-class variants at YAML bind time. See [ADR-0001](docs/adr/0001-equipment-bonus-unified-list.md).
- **Armor defense** — `(SLASH_DEFENSE | PIERCE_DEFENSE | BLUNT_DEFENSE | ENERGY_DEFENSE | MAGICAL_DEFENSE)`, the right-hand term in the combat formula `(attackerStat × weaponPow) - (targetStat × armorDef) × typeModifier`. Targets the wearer's defense stat.
- **Attribute bonus** — `+N` to one of `STRENGTH/DEXTERITY/CONSTITUTION/PERCEPTION/INTELLIGENCE/LUCK`. Feeds *effective* attributes used by derived-pool calculations (max HP from CON, etc.). Does **not** feed `required-attributes` equip checks — those always read **base** attributes to prevent recursive bootstrap.
- **Passive buff** — magnitude added to an existing `ScalingEffect` target (`STAMINA_REGEN`, `BLOCK_CHANCE`, `MOVEMENT_SPEED`, …). Sums into the same aggregator that perk passive auras feed.
- **`required-attributes`** — per-item minimum base attribute thresholds. The `equip_item` reducer rejects with `AttributeRequirementUnmet` on shortfall. An agent who *drops* below a requirement (de-leveling on death) keeps the item equipped — gear is never auto-unequipped on stat changes.
- **Block roll** — defensive roll layered between dodge and hit. Triggers when the agent has aggregated `BLOCK_CHANCE > 0`. On success, damage is reduced by `blockMitigationPct` (in `BalanceLookup`). Mirrors the existing dodge roll structure.

## Sets

- **Equipment set** — a named group of equipment items (e.g. `IRON`) that grants threshold bonuses when the agent has 2 / 4 / 6 / … pieces equipped. Membership lives in `equipment-sets.yaml`; items themselves carry no set metadata. See [ADR-0002](docs/adr/0002-equipment-set-bonuses.md).
- **Set threshold** — count of equipped pieces from a set that unlocks a tier of bonuses. Each threshold grants stat bonuses (same `{target, magnitude}` shape as item bonuses) and may grant triggered passives.
- **Average-rarity scaling** — set bonus magnitudes scale by the mean ordinal rarity of the agent's equipped pieces from that set, mapped through a `rarityMultiplier` curve in `BalanceLookup`. So a 4-piece IRON set with 3 RARE + 1 COMMON pieces averages to ordinal 1.5 → UNCOMMON multiplier 1.25×.
- **Set-only active abilities** — deferred to a follow-up. v1 set thresholds can grant stat bonuses + triggered passives only.

## Recipes (#146-related)

- **Recipe unlock mode** — `Open | ClassPerk(perkId) | ItemLearned(itemId)`. Open recipes are universally visible (subject to skill-level gate); ClassPerk and ItemLearned recipes require a row in `agent_known_recipes` for the agent.
- **`agent_known_recipes` ledger** — per-agent persistent record of recipes unlocked through gameplay (perk pick or scroll consume). Never rolls back on de-level or perk-loss. Idempotent inserts via `onConflictDoNothing`.
- **`RecipeLearned` event** — `AgentEvent.RecipeLearned`, emitted only on a genuinely-new ledger row write. Routed by `AgentEventDispatcher` to the `recipe.learned` topic on the agent's event stream.
- **`UnknownRecipe` collapse** — when an agent tries to craft a locked recipe they haven't unlocked, the craft reducer raises `WorldRejection.UnknownRecipe` rather than a distinct `RecipeNotLearned`. Preserves information asymmetry (design principle #7) — agents can't probe the locked-recipe namespace.
