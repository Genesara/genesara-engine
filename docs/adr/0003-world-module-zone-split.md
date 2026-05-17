# ADR 0003: Split `:world` into zone modules (core + body + combat + economy + environment) with umbrella

**Status:** Accepted — 2026-05-17

## Context

`:world` has grown to 230 Kotlin files across ~32 feature folders. `WorldCommand.kt` is 359 lines, `WorldEvent.kt` is 575 lines; both are single sealed hierarchies covering every verb in the game. Reducers in `internal/<feature>/` may freely import each other — there is no compile-enforced cross-feature boundary inside the module.

Two pains drove this decision:

- **Cognitive load.** Opening `:world` shows 32 flat folders. Nobody can hold the topology in their head.
- **Coupling discomfort.** Combat reaches into `body`, `equipment`, `abilities`, `vision`, `death`, `worldstate`. The reaches are valid today but invisible — there is no rule preventing `crafting` from importing `combat` tomorrow.

[`docs/architecture.md`](../architecture.md) already chose "the Modulith module is the boundary, organize by capability inside it." That decision is preserved — we are not stacking hexagonal layers on top of it. We are subdividing the boundary itself.

## Decision

Split `:world` into **five zone modules + one umbrella**, replacing today's single `:world` module:

```
:world-core            ← static world + tick infra (regions/nodes/positions, balance, behavior, perks dispatcher, mesh, invalidation, editor, say, memory)
:world-body            ← agent presence + survival + carrying (body, death, passive, equipment, inventory, drink, consume, spawn, starter, pickup)
:world-combat          ← violence (combat, abilities, killstreaks, vision)
:world-economy         ← production + exchange (harvest, cultivation, resources, crafting, extract, trade, grounditems)
:world-environment     ← non-player entities + structures (npc, buildings, instances)
:world                 ← umbrella; depends on every zone; hosts tick handler + gateways + JSON config
```

`classes/` (XP progression hooks + level-10/50 emitters) was originally planned to move to `:player`. **Revised during implementation**: all four files (`BehaviorBaselineListener`, `CharacterXpProgression`, `Level10ChoiceEmitter`, `Level50EvolutionEmitter`) depend on `BehaviorTracker` (a world-side per-agent action counter). Moving them to `:player` would invert the existing `:world → :player` dependency direction and create a cycle. They are genuinely world-side adapters that bridge player events ↔ world behavior tracking, not pure player progression. **They remain in `:world`** and will land in `:world-core` (since `BehaviorTracker` is also a core-zone concern). Phase 1.5 is reduced to a no-op.

`:api` imports only the umbrella `:world` (plus `:engine`, `:player`, `:account`, `:admin` as today). Zones do not depend on each other; every zone depends only on `:world-core` (+ `:engine`, `:player`).

### State (A1 — per-zone slices)

`WorldState` is decomposed into per-zone slice data classes:

```kotlin
data class WorldState(
    val core: CoreSlice,                // regions, nodes, positions
    val body: BodySlice,                // bodies, inventories, equipment
    val combat: CombatSlice,            // killStreaks (+ dirty tracking)
    val environment: EnvironmentSlice,  // npcs (+ dirty/removed/cleared tracking)
    // economy slice TBD — many of its current fields are jOOQ-backed reads, not in-memory state
)
```

Each zone owns its slice's `load()` / `save()` (jOOQ repo, Flyway folder). Tick handler loads all slices, threads each to its zone, saves all back.

The "load once per tick" property is preserved per slice; total tick cost is sum-of-loads, parallelisable.

### Commands & events (Option II — per-zone sealed sub-hierarchies + non-sealed marker)

```kotlin
// :world-core
interface WorldCommand {
    val commandId: UUID
    val agent: AgentId
    // discriminator left to Jackson per-zone module registration
}

// :world-body
sealed interface BodyCommand : WorldCommand {
    data class SpawnAgent(...) : BodyCommand
    data class MoveAgent(...) : BodyCommand
    // ...
}
```

**Wire contract is preserved.** Each zone publishes a Jackson `Module` registering its `@JsonTypeName` subtypes (`"spawn"`, `"move"`, `"attack"`, ...). The umbrella's `ObjectMapper` installs all zone modules at boot. Redis queue payloads parse unchanged across the cut-over.

Same shape for `WorldEvent`.

Sealed exhaustiveness shrinks from "global `when` over all commands" to "per-zone `when` over that zone's commands." In practice the global `when` was already only a dispatcher — actual handling is and always was per-zone.

### Reducer contract (P4 — pure reducer returns effects list)

```kotlin
data class ReducerOutput<S>(
    val sliceDelta: S,
    val effects: List<CrossZoneEffect>,
    val events: List<WorldEvent>,
)

internal fun reduceAttack(
    bodyView: BodyReadView,       // read-only handle on body slice
    envView: EnvironmentReadView, // read-only handle on environment slice
    combat: CombatSlice,          // own slice, read+write
    cmd: AttackCommand, balance, tick,
): Either<WorldRejection, ReducerOutput<CombatSlice>>
```

Reducers stay pure (no Spring, no I/O, no clock). Cross-zone reads go through typed `*ReadView` interfaces. Cross-zone writes go through `CrossZoneEffect` (a sealed hierarchy with one variant per inter-zone write):

```kotlin
sealed interface CrossZoneEffect {
    // body-targeted
    data class DamageBody(val agent: AgentId, val amount: Int) : CrossZoneEffect
    data class RestoreStamina(...) : CrossZoneEffect
    // economy-targeted
    data class DropGroundItem(...) : CrossZoneEffect
    // environment-targeted
    data class KillNpc(val npc: NpcId) : CrossZoneEffect
    // ...
}
```

Tick handler routes each effect to the owning zone's effect handler, which mutates that zone's slice and emits external `WorldEvent`s.

### Effect chaining (C3 — single-hop + shared pure math)

Effect handlers do **not** emit further effects. They mutate their slice and publish events. Cross-zone *cascades* (an attack killing an NPC dropping loot bumping a kill streak) are composed by the initiating reducer calling pure math helpers from the relevant zones' public surfaces:

```kotlin
// :world-body public surface
object BodyMath {
    fun applyDamage(body: AgentBody, amount: Int, tick: Long): AgentBody
    fun isDead(body: AgentBody): Boolean
}
```

This generalises today's `DeathProcessor.applyDeath` seam (see [`docs/architecture.md`](../architecture.md) §"The four core shapes"). The math lives once, in the zone that owns the data; reducers and effect handlers call it. Effects only mutate the slice that owns them.

The trade-off: this is a *discipline*, not compile-enforced. The compile-enforced part is "reducers cannot reach into other zones' slices directly." The "share the math" part is convention reinforced by code review.

## Per-zone public surface — four contracts

Every zone module exposes exactly:

1. **`<Zone>ReadView`** — typed read interface (e.g. `BodyReadView { fun bodyOf(agent): AgentBody?; fun hasItem(agent, item): Boolean }`).
2. **`CrossZoneEffect.to<Zone>(...)` variants** — typed write surface other zones can emit at us.
3. **Sealed command sub-hierarchy** — what the zone accepts.
4. **Sealed event sub-hierarchy** — what the zone emits.

Plus **pure math helpers** as public functions on the zone's public package (`BodyMath`, `CombatMath`, ...).

Anything else is `internal/`.

## Sequencing (E1 — one PR, internally phased)

This ADR is the planning artifact. The branch ships as a single PR; internally executed in phases for review legibility:

1. **Phase 1.1** — Reshape `WorldState` into per-zone slice composition. No Gradle changes; sealed hierarchies still flat.
2. **Phase 1.2** — Refactor reducers to `(views, ownSlice, cmd) → ReducerOutput`. One zone at a time (body → economy → environment → combat). Subagents may parallelise once Phase 1.1 lands the slice shape.
3. **Phase 1.3** — Introduce `CrossZoneEffect` sealed hierarchy + per-zone effect handlers. Re-route every cross-zone write through them.
4. **Phase 1.4** — Split `WorldCommand` / `WorldEvent` into per-zone sub-hierarchies + Jackson modules. Add Redis-round-trip test for the wire contract.
5. **Phase 1.5** — Move `classes/` out of `:world` into `:player`.
6. **Phase 2** — Create the 5 zone Gradle modules + the umbrella `:world`. Physically move folders, declare `ModuleMetadata`, split Flyway folders. Repoint `:api` + `:app` imports. Modulith verify on the new graph.

The DB schema is unchanged. The Redis wire contract is unchanged. No data migration.

## Consequences

### Wins

- **Cognitive load**: 32 flat folders → 5 zones, each with ~5–10 sub-features. Opening a zone shows only related code.
- **Coupling enforcement**: zone-to-zone visibility is compile-enforced by Modulith. "Combat may not import from crafting" stops being a convention and becomes a build error.
- **Read & write surfaces are enumerable**: `ReadView` interfaces + `CrossZoneEffect` variants form an explicit inventory of inter-zone coupling. `grep "sealed interface CrossZoneEffect"` shows the whole graph.
- **Public-API hygiene at the zone level**: each zone has the same four-contract shape, mirroring how `:world` has its public surface today.

### Costs

- **Module count: 7 → 11.** More Gradle ceremony, more `ModuleMetadata`, more Flyway folders. Acceptable for a domain this size; would not be acceptable for a smaller codebase.
- **Sealed exhaustiveness narrows from global to per-zone.** In practice this was never a global property — the existing top-level `when` in `WorldReducer.kt` is a dispatcher, and the actual handling is already per-zone.
- **Cross-zone reducers become more verbose**: typed read views + effect lists are more ceremony than `state.copy(bodies = bodies + ...)`. Trade-off accepted for the encapsulation gain.
- **One large PR.** Branch will live for weeks; rebase against `main` repeatedly. No Gradle module added or removed until Phase 2, so most of the branch is reviewable as Kotlin refactoring.

## Alternatives considered

- **Option B (sub-package zones inside `:world` with ArchUnit/Modulith-nested enforcement)** — delivers (b) and (c) without splitting modules. Cheaper. Was the recommendation at the Q2 fork. Rejected because the user wanted real Gradle boundaries with the option to extract later.
- **Option A2 (shared core aggregate, reducers split)** — would have kept `WorldState` as today and only spread reducers across modules. Rejected because it adds Gradle ceremony without delivering encapsulation: zones would still all import the god-object.
- **Option I (flat sealed command hierarchy stays in core)** — would have kept the global `when` but forced every new command to touch `:world-core`. Rejected because it would put commands in a different module from their reducers.
- **Pattern P2 (events drive apply)** — would have re-purposed events as both external notification and internal state mutation. Rejected because the two contracts rarely align 1:1; ghost events would clutter the agent event log.
- **Pattern P3 (mutating tx gateway)** — would have killed reducer purity. Rejected.
- **Effect chaining C2 (bounded iteration to quiescence)** — would have allowed effects to emit further effects. Rejected as over-engineered; Genesara's cascades are 2–3 hops and the iteration-cap + ordering machinery is not earned.

## References

- [`docs/architecture.md`](../architecture.md) — patterns this ADR refines
- [`docs/modules.md`](../modules.md) — module map (will be updated as Phase 2 lands)
- [`docs/persistence.md`](../persistence.md) — static-config + mutable-state pattern that each zone slice follows
