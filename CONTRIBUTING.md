# Contributing

Thanks for considering a contribution. This is an early-stage project — the architecture is settled but most features in [`CLAUDE.md`](CLAUDE.md)'s roadmap are unimplemented. There is plenty to build.

Start by reading [`docs/architecture.md`](docs/architecture.md) — the design constraints (tick-based, pure reducers, Spring Modulith boundaries) shape every PR.

## Local setup

See the Quickstart in [`README.md`](README.md). Once Postgres + Redis are running, `./gradlew test` should pass.

## Adding a new command (the 5-step recipe)

Most game features are a new command in `:world` or `:player`. The shell (`*TickHandler`) and the dispatcher are written once; you only touch five spots per command.

1. **Declare the command** — add a variant to the module's `commands/<Module>Command.kt` sealed interface.
   ```kotlin
   sealed interface WorldCommand {
       data class Gather(override val agent: AgentId, val resource: ResourceType) : WorldCommand
       // ...existing variants
   }
   ```

2. **Declare the events it produces** — add variants to `events/<Module>Event.kt`. One command may emit zero, one, or many events; each event carries a `causedBy: UUID` matching the command's `commandId`.
   ```kotlin
   sealed interface WorldEvent {
       data class ResourceHarvested(
           val agent: AgentId, val node: NodeId,
           val resource: ResourceType, val amount: Int,
           override val tick: Long, val causedBy: UUID,
       ) : WorldEvent
   }
   ```

3. **Declare any new rejection reasons** — add variants to `<Module>Rejection.kt`. Reuse existing reasons when they fit (`UnknownAgent`, etc.).

4. **Write a pure reducer** under `internal/<feature>/<Feature>Reducer.kt`. No Spring, no I/O, no clock — just `(state, command, ..., tick) → Either<Rejection, (newState, event)>`. Use Arrow's `either { }` block: `ensureNotNull`, `ensure`, `raise` keep validation crisp.
   ```kotlin
   internal fun reduceHarvest(
       state: WorldState,
       command: WorldCommand.Harvest,
       tick: Long,
   ): Either<WorldRejection, Pair<WorldState, WorldEvent>> = either {
       val node = ensureNotNull(state.nodeOf(command.agent)) {
           WorldRejection.UnknownAgent(command.agent)
       }
       ensure(node.has(command.resource)) {
           WorldRejection.NodeDepleted(node.id, command.resource)
       }
       val (next, amount) = state.harvest(command.agent, command.resource)
       next to WorldEvent.ResourceHarvested(
           command.agent, node.id, command.resource, amount, tick, command.commandId,
       )
   }
   ```

5. **Wire one branch in the dispatcher** — add a `when` arm in `internal/WorldReducer.kt`.
   ```kotlin
   internal fun reduce(state, command, balance, profiles, tick) = when (command) {
       is WorldCommand.Harvest -> reduceHarvest(state, command, tick)
       // ...other arms
   }
   ```

That's it. The `WorldTickHandler` shell never changes — it loads state once per tick, folds reducers, persists, and publishes events. Add a unit test for the reducer (pure function, no Spring needed) and the slice is done.

If the new command needs to be exposed over MCP, scaffold a tool with the `/new-mcp-tool` skill (see `.claude/skills/new-mcp-tool/`) or copy the layout of an existing tool under `api/src/main/kotlin/.../mcp/tools/`. Each MCP tool has the same shape:

```kotlin
@Component
internal class HarvestTool(
    private val world: WorldCommandGateway,
    private val engine: TickClock,
    private val activity: AgentActivityRegistry,
) {
    @Tool(name = "harvest", description = "Extract a resource at the current node.")
    fun invoke(req: HarvestRequest, toolContext: ToolContext): HarvestResponse {
        touchActivity(toolContext, activity)
        val agent = AgentContextHolder.current()
        val command = WorldCommand.Harvest(agent = agent, resource = req.resource)
        val nextTick = engine.currentTick() + 1
        world.submit(command, appliesAtTick = nextTick)
        return HarvestResponse(commandId = command.commandId, appliesAtTick = nextTick)
    }
}
```

Register the tool in `McpServerConfiguration.gameTools(...)`.

## Testing strategy

- **Reducers**: plain JUnit 5, no Spring. Feed `(state, command)`, assert `(newState, event)`. The existing `MovementReducerTest`, `SpawnReducerTest`, `UnspawnReducerTest` are the templates. Property-test where it makes sense.
- **MCP tools / shells**: plain JUnit + mocks for collaborators. `SpawnToolTest`, `MoveToolTest` are the templates. No Spring boot.
- **Repositories**: `@JooqTest` + Testcontainers Postgres. Round-trip through real SQL.
- **Security filters & auth providers**: instantiate the filter directly, mock the collaborator (`AgentRegistry`, `AdminTokenStore`, `AdminAuthenticator`). The filter has no Spring dependencies.
- **REST controllers**: `@WebMvcTest` slice; mock the gateways/registrars. Don't `@SpringBootTest` for controller-only assertions — boot is too slow.
- **End-to-end tick loop**: `@SpringBootTest` in `:app`. Used sparingly — one or two happy-path tests.
- **Modulith verification**: `ApplicationModules.of(...).verify()` is wired in `app/src/test/.../ModularityTests.kt`. New modules must declare their `allowedDependencies` in `ModuleMetadata.kt`.

## Things to keep in mind

- **Reducers are pure.** No Spring, no I/O, no clock. If you find yourself wanting to inject a `Clock` or a repository into a reducer, the right move is almost always to thread the value through as a parameter or move the I/O up into the shell.
- **Public package = contract.** Anything other modules need goes in the module's top-level package or `commands/` / `events/`. Everything else lives under `internal/`. Spring Modulith verifies this at test time.
- **No FK constraints across module boundaries.** Cross-module references are UUID columns with a comment. See [`docs/persistence.md`](docs/persistence.md).
- **Flyway versions are globally unique** across modules: `world` `V1+`, `account` `V100+`, `player` `V200+`, `admin` `V300+`. Bump the prefix when you create a new module.
- **Don't reintroduce dependencies we deliberately skipped** (Hibernate/JPA, Spring Data JDBC, Axon/Kafka, MapStruct). The reasoning is in [`docs/architecture.md`](docs/architecture.md).

## Commits

Use [Conventional Commits](https://www.conventionalcommits.org/). One change per commit when reasonable.

## PRs

Keep them focused. A new command should ideally be one PR: the command/event/rejection types, the reducer, its test, and (if applicable) the MCP tool. Refactors and feature work should not share a PR.
