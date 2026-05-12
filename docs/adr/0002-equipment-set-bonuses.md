# ADR 0002: Set bonuses — pieces declared in set catalog, average-rarity scaling, triggered passives at threshold

**Status:** Accepted — 2026-05-12

## Context

Set bonuses (full Iron armor → +X) need three shape decisions: (1) how set membership is declared; (2) how per-instance rarity affects the bonus magnitude given mismatched-rarity loadouts; (3) what kinds of behavior a set threshold can grant.

## Decision

### Membership

Set membership lives in a new top-level catalog `world-definition/equipment-sets.yaml`. Items stay agnostic of set systems — no `set-id` field on `Item`.

```yaml
sets:
  catalog:
    IRON:
      pieces: [IRON_HELMET, IRON_CHEST_PLATE, IRON_PANTS, IRON_BOOTS, IRON_GLOVES]
      thresholds:
        2:
          bonuses: [{ target: SLASH_DEFENSE, magnitude: 2 }]
        4:
          bonuses: [{ target: CONSTITUTION, magnitude: 3 }]
          triggered-passives:
            - trigger: ON_HIT_TAKEN
              effect-kind: REFLECT_DAMAGE
              params: { multiplierPct: "10" }
              internal-cooldown-ticks: 5
```

A startup `EquipmentSetReferentialValidator` enforces: every id in `pieces` resolves in `ItemLookup` AND has `category: EQUIPMENT`. An item can appear in multiple sets.

**Threshold stacking.** Tiers stack additively — wearing 4 IRON pieces grants the 2-piece bonus AND the 4-piece bonus (Diablo / WoW convention). Each threshold defines the bonuses *added at that tier*, not the total for that tier.

### Rarity scaling

Item rarity is per-instance (`EquipmentInstance.rarity`, rolled at craft). With mismatched-rarity loadouts (3 Legendary IRON + 1 Common IRON), the set magnitude scales by the **average rarity** of the equipped pieces from that set:

```
multiplier(avgOrdinal) where avgOrdinal = mean(rarity.ordinal across equipped set pieces)
rarityMultiplier = {COMMON: 1.0, UNCOMMON: 1.25, RARE: 1.5, EPIC: 1.75, LEGENDARY: 2.0}
```

Average ordinal is rounded to the nearest tier for the multiplier table lookup. Configurable in `BalanceLookup` so the curve is tunable without a code change.

### Threshold behavior shapes

A set threshold can grant **stat bonuses + triggered passives** but **not active abilities** in v1. Triggered passives re-use the existing `TriggeredPassiveDispatcher` plumbing — set membership is treated as another firing source alongside chosen perks.

Active abilities (`use_ability(SET_BONUS_ID)`) are out of scope: they introduce a parallel ability namespace, a new cooldown store, and a validator path to dedupe set-ability ids against perk-ability ids. Deferrable; can land later without breaking this shape.

## Consequences

- **Pro:** Items stay clean of cross-cutting metadata. Adding a new set is a single file change.
- **Pro:** Mismatched-rarity loadouts feel gradient-y rather than cliff-y — agents are encouraged to upgrade pieces incrementally.
- **Pro:** No new behavior pipeline — set triggered passives ride the existing `TriggeredPassiveDispatcher`.
- **Con:** Reverse-index on item → containing sets has to be built at startup (cheap; analogous to `RecipeUnlockIndex` from #146).
- **Con:** Average-rarity rounding has edge cases (3 Common + 2 Legendary averages to ordinal 1.6 → UNCOMMON, not RARE). Documented; tunable curve absorbs the rough edges.

## Alternatives considered

- **`set-id: IRON` on each item.** Rejected — items end up with set-specific cross-cutting metadata, and an item can't easily belong to multiple sets.
- **Lowest-rarity scaling.** Rejected — creates a perverse incentive (keep an old Common piece to preserve set bonus tier instead of upgrading to a higher-rarity non-set piece).
- **Active abilities at set thresholds.** Deferred — adds three new infra pieces (lookup, cooldown store, validator) for limited v1 design value. Can land later as a follow-up without breaking this ADR.
