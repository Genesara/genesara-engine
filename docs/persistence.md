# Persistence patterns (jOOQ + Flyway)

> *Patterns and rationale, not API reference. When this doc conflicts with the code, the code wins.*

Implementation companion to [`architecture.md`](architecture.md). Read this before adding a new persistence-backed module — it captures decisions and footguns that aren't obvious from the code.

## TL;DR

- **jOOQ + Flyway**, never Hibernate/JPA, never Spring Data JDBC.
- Build wiring is a one-shot via the **`genesara.jooq-module` convention plugin**. Don't copy/paste the underlying jOOQ block.
- **Codegen runs offline.** jOOQ's `DDLDatabase` parses `V*.sql` files directly — no live Postgres needed at build time.
- Domain types stay immutable Kotlin data/value classes. jOOQ Records live inside `<module>.internal.jooq.*` and never cross module borders.
- **No FK constraints across module boundaries.** Cross-module references are plain UUID columns with a comment.
- All modules share one `flyway_schema_history`, so each module owns a disjoint version range. The convention is documented at the module's `build.gradle.kts` — bump the prefix when you create a new module.

## The convention plugin's role

A jOOQ-backed module applies `genesara.spring-module` + `genesara.jooq-module` and configures only two knobs in its `build.gradle.kts`:

```kotlin
jooqModule {
    migrationsSubdir.set("<module>")             // db/migration/<module>/
    tableIncludes.set("table_a|table_b|table_c") // pipe-separated regex; scopes codegen to this module
}
```

Then add the module's migration path to `application.yaml`'s `spring.flyway.locations`. The plugin handles the rest: parser-mode codegen against the SQL files, lowercase identifier folding (critical — without it, generated names won't match Postgres' unquoted-identifier rules), Flyway-ordered script application, and wiring `compileKotlin.dependsOn(generateJooq)` so a fresh checkout builds in one shot.

## The `R__*.sql` escape hatch (stored procedures, functions, plpgsql)

The jOOQ-OSS parser **cannot parse `CREATE FUNCTION`, `CREATE PROCEDURE`, `DO` blocks, or anything plpgsql**. It will fail codegen with `Feature only supported in pro edition: ...`. Even `LANGUAGE SQL` bodies with dollar-quoting or `RETURNS TABLE(...)` are hit-or-miss. Don't fight it.

**Pattern:**

1. Put the function/procedure in a Flyway **repeatable** migration: `R__<purpose>.sql`. Flyway re-runs these on checksum change; `CREATE OR REPLACE` makes them idempotent.
2. The convention plugin's codegen `scripts` glob is `V*.sql` only — `R__*.sql` is invisible to jOOQ-meta. Spring Boot's runtime Flyway picks both up automatically.
3. Call the function from Kotlin via raw SQL. jOOQ has no generated routine binding, and you don't need one:

```kotlin
// illustrative shape — call a plpgsql function via parameterized raw SQL
dsl.resultQuery(
    "SELECT node_id FROM fn_nodes_within({0}, {1})",
    DSL.value(origin.value, SQLDataType.VARCHAR),
    DSL.value(radius, SQLDataType.INTEGER),
).fetch { (it.get(0) as String).let(::NodeId) }.toSet()
```

Live example: `world/src/main/resources/db/migration/world/R__functions.sql` defines `fn_nodes_within` (recursive CTE BFS over node adjacency).

## Static-config + mutable-state aggregates

When an aggregate is mostly config (regions/nodes — never change after seed) with only a small slice that mutates per tick (positions/bodies), don't requery everything every `load()`. Pattern used in `:world`:

```kotlin
// illustrative shape — the static slice is loaded once and held in a volatile cache;
// load() composes it with a fresh query of the mutable slice
@Component
internal class WorldStaticConfig {
    @Volatile private var _regions: Map<RegionId, Region> = emptyMap()
    @Volatile private var _nodes:   Map<NodeId, Node>     = emptyMap()
    // exposed as immutable views; loaded at @PostConstruct, reloaded after editor writes
}

internal class JooqWorldStateRepository(...) : WorldStateRepository {
    override fun load() = WorldState(
        regions   = staticConfig.regions,
        nodes     = staticConfig.nodes,
        positions = fetchPositions(),  // only the mutable rows hit the DB
        bodies    = fetchBodies(),
    )

    @Transactional
    override fun save(state: WorldState) {
        // upsert ONLY positions + bodies; static config is read-only at runtime
    }
}
```

The aggregate API stays `load(): State` / `save(state: State)`, so reducers don't care that part of the state is cached. The static config bean is also injected into the read-side query gateway for point lookups that don't need a DB roundtrip.

When the editor writes (paints a region, seeds a hex grid), it calls `staticConfig.reload()` so the runtime cache reflects the change without a restart.

## Cross-module references

Modulith's boundary is the source of truth, not the DB's referential integrity. So:

- A `world.agent_positions.agent_id` UUID refers to `player.agents.id` — but **no `REFERENCES` constraint**.
- A `player.agents.owner_id` UUID refers to `account.players.id` — but **no `REFERENCES` constraint**.
- Document the soft reference with a SQL comment in the migration.

This keeps each module independently migratable and avoids dragging modules into the same schema-history ordering of foreign-key dependencies. If you want referential integrity, get it in Kotlin via the owning module's gateway, not in SQL.

## Things we deliberately don't do

- **Spring Data JDBC** — replaced by jOOQ. Don't reintroduce it.
- **Hibernate / JPA** — managed-entity state, lazy loading, N+1 surprises. We want explicit SQL.
- **PG enum types** (`CREATE TYPE … AS ENUM`) — they're a pain for codegen and migrations. Store enums as `VARCHAR(32)` and `EnumClass.valueOf(it)` at the store boundary.
- **Generated jOOQ POJOs / DAOs** — only `isRecords = true` is on. Records map to immutable domain types at the store; we don't propagate them.
- **`flyway-gradle-plugin`** — redundant with Spring Boot's runtime Flyway auto-migrate. Removed deliberately.

For the steps to add a new domain feature (command → reducer → event → tool), see the 5-step recipe in [`architecture.md`](architecture.md). New tables fall under steps 4–5 — you just author the migration in the right module's `db/migration/<module>/` folder and update `tableIncludes` so codegen picks the table up.
