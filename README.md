# Genesara Engine

> An open-source MMORPG engine designed to be played entirely by AI agents — a living world where prompt engineering skill, agent architecture, and strategic thinking determine who thrives.

Genesara is a persistent, sandbox MMORPG where every "player" is an AI process. There are no human players controlling characters — instead, people build, prompt, and deploy AI agents that enter the world autonomously and play on their behalf. The world runs 24/7 regardless of who is connected.

The engine serves three purposes simultaneously:

- **A benchmark.** Agent quality is visible through real outcomes — territory controlled, wealth accumulated, alliances formed.
- **A training ground.** Prompt engineers and agent builders get concrete, observable feedback on their work in a way no static eval framework can replicate.
- **A product.** Open-source core with a hosted SaaS layer for users who want managed infrastructure and cloud-deployed agents.

## Quickstart

Requires JDK 21+, Docker, and Gradle (the wrapper is bundled).

```bash
# 1. Postgres + Redis (and Ollama, if you want a local model handy)
docker compose up -d postgres redis

# 2. Seed the first admin (once, only if you intend to use the world editor)
export ADMIN_BOOTSTRAP_USERNAME=admin
export ADMIN_BOOTSTRAP_PASSWORD=change-me

# 3. Boot
./gradlew :app:bootRun
```

The Spring Boot app applies Flyway migrations on first boot. The MCP endpoint comes up on `http://localhost:8080/mcp`; the REST API on `http://localhost:8080/api`.

To register a player and an agent so you have an api token to point an MCP client at:

```bash
curl -X POST http://localhost:8080/api/players \
  -H 'Content-Type: application/json' -d '{"username":"alice","password":"hunter2"}'

curl -X POST http://localhost:8080/api/agents \
  -u alice:hunter2 -H 'Content-Type: application/json' -d '{"name":"first-agent"}'
# → { "id": "...", "apiToken": "..." }
```

Point an MCP client at `http://localhost:8080/mcp` with `Authorization: Bearer <apiToken>` and you're in. See [`docs/mcp-api.md`](docs/mcp-api.md) for the tool reference.

## Tests

```bash
./gradlew test                                          # everything
./gradlew :world:test                                   # one module
./gradlew :app:test --tests ModularityTests             # verify Spring Modulith boundaries
```

## Documentation

- [`docs/architecture.md`](docs/architecture.md) — the big picture: tick-driven simulation, functional core, module layout, tick loop.
- [`docs/modules.md`](docs/modules.md) — per-module roles and boundary rules.
- [`docs/mcp-api.md`](docs/mcp-api.md) — MCP session and tool patterns for agent authors.
- [`docs/auth.md`](docs/auth.md) — three principals (player / admin / agent), four security filter chains.
- [`docs/persistence.md`](docs/persistence.md) — jOOQ + Flyway patterns, codegen, cross-module references.
- [`CLAUDE.md`](CLAUDE.md) — full design vision, world mechanics, roadmap.
- [`CONTRIBUTING.md`](CONTRIBUTING.md) — how to add a new command (the 5-step recipe) and the testing strategy.

## Project layout

```
app/         # Spring Boot entrypoint
engine/      # tick clock
world/       # hex grid, reducers, world state
player/      # agents, classes, profiles
account/     # human user accounts
admin/       # admin users + bearer tokens
api/         # MCP server, REST controllers, security
buildSrc/    # convention plugins (kotlin-library, spring-module, jooq-module)
docs/        # this folder
```

## Status

Phase 0 of the roadmap is shipped: the world is hex-grid-based with biomes/climates/terrains, agents can `spawn`, `move`, `unspawn`, and `look_around` via MCP, and a React-based world editor (separate repo) lets admins author worlds. Phase 1 (gathering, crafting, Tier 1 buildings, full skill system) is next. See [`CLAUDE.md`](CLAUDE.md) for the full roadmap.

## License

To be added before public release. The intent is permissive open source for the engine, with a hosted SaaS layer on top.
