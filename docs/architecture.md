# Architecture

> *Patterns and rationale, not API reference. When this doc conflicts with the code, the code wins.*

## Premise

- **Tick-based simulation + event-driven domain + functional core / imperative shell.**
- The **Spring Modulith module is the boundary** — we don't stack hexagonal ports/adapters on top of it.
- Inside a module, organize **by capability** (feature folders), not by technical layer (`controller/service/repo`).
- **Reducers are pure**; I/O lives in a thin shell around them.

## Module dependency graph

```mermaid
graph LR
    engine[":engine<br/><sub>Tick clock</sub>"]
    account[":account<br/><sub>Player accounts</sub>"]
    admin[":admin<br/><sub>Admin users + tokens</sub>"]
    player[":player<br/><sub>Agents + classes</sub>"]
    world[":world<br/><sub>Hex grid, reducers</sub>"]
    api[":api<br/><sub>MCP + REST</sub>"]
    app[":app<br/><sub>Spring Boot entrypoint</sub>"]

    player --> engine
    player --> account
    world --> engine
    world --> player
    api --> engine
    api --> world
    api --> player
    api --> account
    api --> admin
    app --> api
    app --> world
    app --> player
    app --> engine
    app --> account
    app --> admin

    classDef leaf fill:#eef,stroke:#446
    classDef hub fill:#fef,stroke:#844
    class engine,account,admin leaf
    class api,world hub
```

Every dependency is declared in the module's `ModuleMetadata` and verified by Spring Modulith at test time. The `internal/` package of one module is unreachable from another — the public package is the contract.

## The four core shapes

Every domain feature in `:world` and `:player` boils down to four shapes:

```kotlin
// 1. Command — an intent coming in
sealed interface WorldCommand { /* MoveAgent, SpawnAgent, ... */ }

// 2. Event — a fact, published after a command is accepted
sealed interface WorldEvent  { /* AgentMoved, AgentSpawned, ... */ }

// 3. State — the thing commands fold over
data class WorldState(/* nodes, positions, bodies, ... */)

// 4. Reducer — pure (state, command, tick) → Either<Rejection, (state', events)>
internal fun reduceXxx(state, command, tick): Either<Rejection, Pair<State, List<Event>>>
```

A reducer has **no Spring, no I/O, no clock**. Trivially unit-testable. Everything stateful (DB, bus, scheduler) is the imperative shell that *calls* reducers.

The reducer return type is `List<WorldEvent>` (not a single event) because some verbs naturally emit more than one fact: an `attack` that lands a killing blow emits `AgentAttacked` AND `AgentDied` in the same tick, and the harvest reducer is wired to emit `NodeResourceDepleted` alongside `ResourceHarvested` once that signal lands. Single-event reducers wrap their event in `listOf(event)` — the cost is one allocation per accept.

Death is a shared seam: per-agent death side-effects (XP penalty, kill-streak drop roll, position removal, event emission) live in `DeathProcessor.applyDeath`. The post-passive death sweep (starvation) calls it with `cause = null`; the attack reducer calls it inline on the killing blow with `cause = AttackCause(commandId, attackerId)` so `AgentDied.causedBy` carries the killing command's id and the attacker's `killStreak` ticks up at the same moment as the death event.

## The tick loop

```mermaid
sequenceDiagram
    autonumber
    participant Sched as :engine TickScheduler
    participant Bus as Spring event bus
    participant Tick as :world WorldTickHandler
    participant Repo as WorldStateRepository
    participant Q as RedisCommandQueue
    participant R as reduce()
    participant Disp as :api AgentEventDispatcher
    participant Log as RedisAgentEventLog
    participant MCP as Agent (MCP)

    loop every game.tick.interval
        Sched->>Bus: publish Tick(n, now)
        Bus->>Tick: onTick(Tick)
        Tick->>Repo: load() WorldState
        Tick->>Tick: applyPassives(state, balance, n)
        Tick->>Q: drainFor(worldId, n)
        loop for each command
            Tick->>R: reduce(state, cmd, balance, profiles, n)
            R-->>Tick: Either<Rejection, (state', event)>
        end
        Tick->>Repo: save(state')
        Tick->>Bus: publish WorldEvent(s)
        Bus->>Disp: on(WorldEvent)
        Disp->>Log: append(agent, event)
        Disp-->>MCP: notifications/resources/updated<br/>agent://{id}/events
    end
```

Per tick (in order): the scheduler bumps an atomic counter and publishes `Tick`; the world handler loads state once, applies passives (stamina/health regen), drains commands queued for `n`, folds them through reducers, saves only the mutable rows, and publishes accepted events; the api dispatcher fans events out to per-agent logs and notifies subscribed MCP sessions. Rejections are logged and dropped — no event for now.

### Redis-per-world command queue

Commands ride a Redis list keyed by world and tick: `world:{w}:queue:{tick}`. `WorldCommandGateway.submit` resolves the agent's world via `agent_positions.world_id`, reads `world:{w}:tick`, clamps the requested `appliesAtTick` to `max(currentTick + 1, requested)` so a stale-`TickClock` pod can't queue into an already-drained tick, then `LPUSH`es the JSON payload. The lease holder for that world drains via a Lua-atomic `LRANGE 0 -1 + DEL` inside `WorldTickHandler.tickOne` and folds the commands through reducers as before.

`WorldCommand` carries explicit `@JsonTypeInfo` / `@JsonSubTypes` discriminators (`"move"`, `"attack"`, `"useAbility"`, …). The strings are a wire contract: a Kotlin rename keeps them, a discriminator change silently breaks in-flight queues across pods.

`submit` returns the actual landing tick synchronously, so MCP-tool responses surface the clamped value to the agent — routine lease handover is invisible. The "missing ack" prompt that the in-process queue's docstring leaned on no longer exists: every submit returns a tick. Recovery against orphans (a Redis flush mid-flight, or the microsecond race window between the `currentTick` read and the `LPUSH` where a parallel drain on the just-bumped tick takes the payload before it lands) is the agent's responsibility — watch for the resulting event up to the returned tick plus a small slack, and re-issue if absent. Every `WorldCommand` carries a `commandId: UUID` which becomes `causedBy` on the resulting event, so retries are idempotent at the agent level. Tightening the residual race would require folding the GET into the LPUSH via Lua and isn't worth the complexity yet.

### Tuning

| Knob | Where | Notes |
|---|---|---|
| Tick interval | `application.tick.interval` | ISO-8601 duration. Default `PT5S`. |
| Idle auto-unspawn | `application.presence.timeout` / `application.presence.reaper-interval` | Defaults 30 min and 1 min. |
| Event log TTL / cap | `application.events.ttl` / `application.events.backlog-cap` | Bounds Redis memory per agent. Defaults `PT1H` / `500`. |

## Module structure

Each domain module is organized by capability, not by layer. Public surface = commands, events, IDs, value objects, gateways. Everything else is `internal/`. Inside `internal/`, each feature folder owns one reducer + its helpers + its tests; no cross-folder imports.

```
world/.../world/
├── ModuleMetadata.kt
├── Node.kt, Region.kt, ...        # public value objects / IDs
├── commands/WorldCommand.kt        # public command API
├── events/WorldEvent.kt            # public domain events
└── internal/
    ├── movement/                   # one feature = one folder
    ├── spawn/
    ├── passive/
    ├── worldstate/                 # the aggregate + persistence
    └── tick/                       # the imperative shell
```

Spring Modulith enforces `internal/` is unreachable from other modules at verification time.

## Adding a new command (the 5-step recipe)

Every new domain feature follows the same shape. The shell (`*TickHandler`) and the dispatcher are written once; you only touch five spots per command.

1. **Declare the command** — add a variant to `commands/<Module>Command.kt`.
2. **Declare the events it produces** — add variants to `events/<Module>Event.kt`. Zero, one, or many per command.
3. **Declare any new rejection reasons** — add variants to `<Module>Rejection.kt`. Reuse existing reasons when they fit.
4. **Write a pure reducer** under `internal/<feature>/<Feature>Reducer.kt`. No Spring, no I/O, no clock. Use Arrow's `either { }` block — `ensureNotNull`, `ensure`, `raise` keep validation crisp.
5. **Wire one branch in the dispatcher** — add a `when` arm in `internal/<Module>Reducer.kt`.

Illustrative reducer shape:

```kotlin
// pure: no Spring, no I/O — feed (state, command), get back the next state + event or a rejection
internal fun reduceHarvest(state, command, tick): Either<WorldRejection, Pair<WorldState, WorldEvent>> = either {
    val node = ensureNotNull(state.nodeOf(command.agent)) { UnknownAgent(command.agent) }
    ensure(node.has(command.resource)) { NodeDepleted(node.id, command.resource) }
    val (next, amount) = state.harvest(command.agent, command.resource)
    next to ResourceHarvested(command.agent, node.id, command.resource, amount, tick)
}
```

That's it. The tick handler shell never changes — load once, fold reducers, persist, publish. Add a unit test for the reducer (pure function, no Spring needed) and the slice is done. The same shape applies to `:player` and any future domain module.

## MCP integration (`:api`)

MCP tool handlers are thin adapters. They translate tool calls to commands and either:

- **Queue for next tick** — default for state-mutating tools (`spawn`, `move`, `unspawn`). Returns `{ commandId, appliesAtTick }` so the agent knows when its action resolves. Fair scheduling, no races, deterministic.
- **Resolve synchronously** — for pure reads (`look_around`). Hits the read model, not the reducer.

`:api` only imports from `:world`'s public packages (`commands`, `events`, IDs, gateways). It never reaches into `internal/`. See [`mcp-api.md`](mcp-api.md).

## Persistence

- **One writable aggregate per module** (`WorldState` in `:world`). Writes go through the reducer + shell.
- **jOOQ over Hibernate/JPA.** Explicit SQL, compile-time-checked queries, immutable Kotlin domain types. jOOQ Records stay inside the module's `internal.jooq.*` package and never cross module borders.
- **Flyway** migrations, one folder per module. Versions are globally unique across modules. Cross-module DB references are plain UUID columns with a comment — no FK constraints across module boundaries.

See [`persistence.md`](persistence.md) for the patterns: convention plugin, offline DDLDatabase codegen, the `R__*.sql` escape hatch, the static-config caching pattern.

## Libraries we add

| Purpose | Artifact | Why |
|---|---|---|
| Persistence | `spring-boot-starter-jooq` | Explicit SQL, compile-time-checked queries, immutable types — no JPA managed-entity overhead. |
| Migrations | `flyway-core` + `flyway-database-postgresql` | Schema versioning, module-scoped folders. |
| Command validation | `spring-boot-starter-validation` | Bean validation on the MCP boundary only. |
| Functional core | `arrow-core`, `arrow-fx-coroutines`, `arrow-optics` | `Either`/`Raise` for reducer error flow; optics for nested state updates. |
| MCP server | `spring-ai-starter-mcp-server-webmvc` | First-class MCP support — declarative `@Tool` methods + resource subscriptions. |
| Event log / cache | Redis (Jedis) | Per-agent event outbox with TTL + backlog cap. |
| Test scoping | `spring-modulith-starter-test` | `@ApplicationModuleTest` boots one module. |

## Libraries we intentionally skip

- **Axon / EventStore / Kafka / RabbitMQ** — overkill. Postgres + Spring's in-process event bus + Redis covers reliability for now. Introduce a broker only when a module is extracted into its own service.
- **Full JPA** — don't need a managed object graph for this domain.
- **MapStruct / model-mapper** — Kotlin extension functions are cheaper.
- **Hexagonal ports/adapters** — the Modulith public package already *is* the port; its `internal/` already *is* the adapter boundary.

## Code style

The architecture above is the *macro* shape; the lines below are the *micro* discipline that keeps modules navigable.

- **Enums (and sealed types) over strings.** Any value with a closed set of variants — resource kinds, building types, rejection reasons, equipment slots, item rarities, command/event discriminators — must be modelled as an `enum class` or `sealed interface`/`sealed class`. Never pass a `String` where an enum would work. Strings cross module boundaries only at the serialization edge (jOOQ codegen, MCP JSON, Flyway migrations); inside the domain everything is typed. If you find yourself writing `when (kind) { "wood" -> ...; "iron" -> ... }`, stop and introduce the enum.
- **Zero comments by default.** Code, function names, parameter names, and test names carry the meaning. The only survivors are `TODO(<tag>): ...` markers, one-line KDoc on public-API surfaces, and a short *WHY* when an invariant is genuinely non-obvious. No "this block does X" running commentary, no test-setup comments restating literals, no KDoc on private helpers. Re-read every comment in a diff before committing — delete unless it falls into an allowed bucket. Full rules in [`CLAUDE.md`](../CLAUDE.md#code-style-self-explanatory-code).
- **Clean architecture, applied through the Modulith.** The public package *is* the port; `internal/` *is* the adapter boundary. Reducers stay pure (no Spring, no I/O, no clock); the shell is the only thing that touches the world. Dependencies always point inward — `:api` may import `:world`'s public surface, never the reverse. Do not stack hexagonal ports/adapters on top of this; the Modulith already gives us the layering.
- **Clean code, applied surgically.** Smallest diff that solves the stated problem. No speculative abstractions, no "while I'm here" refactors, no flexibility that wasn't requested. Match the existing style in the file you're editing even if you'd write it differently. Every changed line must trace to the user's request.

## Testing strategy

- **Reducers**: plain JUnit 5, no Spring. Feed `(state, command)`, assert `(state', events)`. Fast, deterministic, property-testable.
- **Tools / shells**: plain JUnit + mocks for collaborators.
- **Repositories**: `@JooqTest` + Testcontainers Postgres. Round-trip through real SQL.
- **End-to-end tick loop**: `@SpringBootTest` in `:app`. Used sparingly.
- **Modulith verification**: `ApplicationModules.of(...).verify()` in `:app`.

---

Companion docs: [`modules.md`](modules.md) (per-module map), [`mcp-api.md`](mcp-api.md) (tool patterns), [`persistence.md`](persistence.md) (jOOQ + Flyway patterns), [`auth.md`](auth.md) (security flows).
