# ADR 0001: Equipment bonuses are a single heterogeneous list

**Status:** Accepted — 2026-05-12

## Context

Issue #147 enriches `Item` with first-class equipment metadata (armor defense, attribute bonuses, passive buffs, set bonuses). The combat formula `(attackerStat × weaponPow) - (targetStat × armorDef) × typeModifier` already assumes a `targetStat × armorDef` term on the defender side, but no field on `Item` carries `armorDef` today. Armor pieces currently exist as catalog entries with `category: EQUIPMENT` but no defensive value — they're inert in combat.

Three distinct bonus kinds need a home on equipment:

1. **Defensive value per damage type** — `(SLASH_DEFENSE, magnitude)`, fed into the combat formula.
2. **Attribute bonuses** — `(CONSTITUTION, +5)`, modifying derived pools (max HP, max stamina, etc.) but **not** input to `required-attributes` equip checks (otherwise gear is recursively bootstrap-able).
3. **Passive buffs** — re-uses existing `ScalingEffect` targets (STAMINA_REGEN, BLOCK_CHANCE, MOVEMENT_SPEED, …).

## Decision

A single optional `bonuses:` field on each equipment item carrying a heterogeneous list of `{ target, magnitude }` entries. The binder dispatches on `target` enum membership:

- `target ∈ DamageType` → `EquippedBonus.ArmorDef(damageType, magnitude)`
- `target ∈ Attribute` → `EquippedBonus.AttributeBonus(attribute, magnitude)`
- `target ∈ ScalingEffect` → `EquippedBonus.PassiveBuff(effect, magnitude)`

Validator rejects unknown targets at app boot.

```yaml
IRON_CHEST_PLATE:
  category: EQUIPMENT
  valid-slots: [CHEST]
  max-durability: 200
  required-attributes:
    strength: 20    # checked against BASE attributes only
  bonuses:
    - { target: SLASH_DEFENSE,   magnitude: 8 }
    - { target: PIERCE_DEFENSE,  magnitude: 6 }
    - { target: CONSTITUTION,    magnitude: 5 }
    - { target: STAMINA_REGEN,   magnitude: 2 }
```

At runtime, an `EquipmentBonusAggregator` walks equipped instances and produces three bucketed views (`ArmorDefByDamageType`, `AttributeBonuses`, `PassiveBuffs`) for downstream consumers.

`required-attributes` checks query the agent's **base** attributes only — never the effective (base + equipped) attribute view. Otherwise wearing a +CON ring qualifies the agent for a +CON breastplate, recursively. Effective attributes feed derived-pool calculations (max HP from CON), not gating logic.

## Consequences

- **Pro:** YAML is uniform across all equipment items — authors learn one `bonuses:` syntax, not three section-specific shapes.
- **Pro:** Kotlin model remains type-safe via the sealed `EquippedBonus`; consumers can `when (bonus)` exhaustively.
- **Pro:** New bonus kinds (e.g. resistances, on-hit triggers) extend the sealed type without touching unrelated equipment.
- **Con:** YAML readers can't tell at a glance that `SLASH_DEFENSE` is a defense vs `CONSTITUTION` is an attribute — they must consult the enum lists. Mitigated by the validator rejecting unknown targets with a clear error pointing at the offending line.
- **Con:** Authors could write nonsense like `{ target: SLASH_DEFENSE, magnitude: 8 }` on a weapon. Mitigated by an optional category-aware validator pass (out of scope for the initial slice).

## Alternatives considered

- **Typed sub-sections** (`armor-def:`, `attribute-bonuses:`, `passive-buffs:`). Tighter schema but YAML becomes verbose and authors duplicate magnitude semantics across sections. Rejected.
- **Defer attribute and buff fields**, ship armor-def only in v1. Rejected: catalog churn is high once items exist; better to land the unified shape once.
