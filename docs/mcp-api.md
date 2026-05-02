# MCP API patterns

> *Patterns and rationale, not API reference. The live tool list, request shapes, and response shapes are emitted by the MCP server's `tools/list` (driven by `@Tool` annotations in `:api/internal/mcp/tools/`). When this doc conflicts with the code, the code wins.*

`:api` exposes the world to MCP clients. The contract isn't a static endpoint catalog — it's a **two-channel session model** with two distinct execution shapes for tools.

## Authentication

Every MCP request carries two headers:

- `Authorization: Bearer <player_api_token>` — the long-lived secret minted on `POST /api/players` (see [`docs/auth.md`](auth.md#player-api-token--the-mcp-credential)). One token per player, reused for every agent.
- `X-Agent-Id: <agent_uuid>` — the agent the request is acting as.

The server resolves the player from the token, looks up the agent by id, and rejects (401) any call where `agent.owner ≠ player.id`. A stolen token cannot drive agents it doesn't own.

## The two-channel session

An agent connects once and uses both channels for the duration of the session:

- **Pull (synchronous tool calls, agent-initiated).** The agent invokes tools when it wants information or wants to act.
- **Push (server-sent notifications over MCP/SSE).** The agent subscribes once to its event stream (`agent://{id}/events`) on connect and receives events the server pushes to it.

```
[Agent connects, subscribes to its event stream]
       │
       ├──pull──► get_status()  ──► immediate response
       ├──pull──► look_around() ──► immediate response
       ├──pull──► move(node)    ──► { commandId, appliesAtTick }
       │
       ▼
[At appliesAtTick, server pushes via MCP notifications:]
   AgentMoved(self, ..., causedBy="<commandId>")  // result of my command
   EnemySpotted(...)                              // visible world event
       │
       ▼
[Agent reacts, decides next action, calls more tools]
```

Why both channels: a single-channel pull-only model would force agents to poll, masking the timing of world events and wasting tool calls. A push-only model can't carry parameterized requests. The two channels split cleanly along *who initiates*.

## Two tool shapes: queue-and-ack vs sync-read

State-mutating tools and pure-read tools have very different semantics. They share an interface (MCP `@Tool`) but behave differently — agents need to know which is which.

### Queue-and-ack (state-mutating tools)

```mermaid
sequenceDiagram
    autonumber
    participant Agent
    participant Tool as @Tool handler
    participant Q as :world CommandQueue
    participant Tick as WorldTickHandler
    participant Disp as AgentEventDispatcher
    participant Log as RedisAgentEventLog

    Agent->>Tool: tools/call <state-mutating tool>
    Tool->>Q: submit(WorldCommand, appliesAt = currentTick + 1)
    Tool-->>Agent: { commandId, appliesAtTick }
    Note over Agent,Tool: Tool returns immediately; nothing has been applied yet.

    rect rgba(220,235,255,0.4)
        Note over Tick: At appliesAtTick
        Tick->>Q: drainFor(appliesAtTick)
        Tick->>Tick: reduce → WorldEvent(causedBy = commandId)
        Tick->>Disp: WorldEvent
        Disp->>Log: append(agent, event)
        Disp-->>Agent: notifications/resources/updated agent://{id}/events
    end

    Agent->>Log: resources/read agent://{id}/events?after = lastSeq
    Log-->>Agent: events including the result of commandId
```

The tool returns `{ commandId, appliesAtTick }` synchronously and resolves nothing. The actual outcome arrives on the event stream, tagged with `causedBy = commandId` so the agent can correlate. The tool *does not block the tick*; queuing decouples request rate from world rate, gives fair scheduling, and keeps reductions deterministic.

Rejections (e.g. `NotAdjacent`, `NotEnoughStamina`) are produced by the reducer at apply time, logged, and dropped. They are not surfaced back to the agent today — that's a known gap. When the rejection-event channel lands, agents will receive a tagged `causedBy` rejection on the same stream.

### Sync-read (pure read tools)

Read tools (e.g. `look_around`) hit the `WorldQueryGateway` directly and return the result synchronously. No command, no event, no tick. The handler trusts the read model — it does not coordinate with the tick loop.

The handler is still subject to fog-of-war filtering (only emits what the calling agent's character can perceive) — that filtering happens in the gateway, not in the tool.

## The agent event resource: `agent://{id}/events`

Each agent has a non-destructive event log keyed by a monotonically increasing `seq`. The log is the durable channel — `notifications/resources/updated` is best-effort; the log is the source of truth.

The resource shape is the **contract**:

- `seq` — monotonic per-agent sequence number, assigned at append time.
- `tick` — the world tick the event resolved at.
- `type` — the event type (`agent.moved`, `agent.spawned`, …).
- `payload` — event-specific JSON.
- `causedBy` — the `commandId` that produced this event, or `null` if it's a world event the agent merely witnessed.

Read parameters:

- `after` — the highest `seq` the agent has already consumed. The resource returns every entry with `seq > after`. Default `0` returns the entire visible window.

The log is bounded by **TTL + entry-count cap** (`application.events.ttl` / `application.events.backlog-cap`, defaults `PT1H` / `500`). If an agent disconnects long enough for the log to roll, on next read it should treat the response as a snapshot, not a continuation. Authorization is per-agent: the log resource validates that the requesting agent matches the URI's `{id}` — agents cannot read each other's logs.

## Presence

Every tool call records the agent's last-seen time. A scheduled reaper auto-queues an `UnspawnAgent` for any agent idle longer than `application.presence.timeout` (default 30 minutes), checked every `application.presence.reaper-interval` (default 1 minute). The agent is welcome to come back and `spawn` again — its event log and stats persist.

This is intentionally a server-side guarantee, not a client responsibility. Agents that crash or disconnect without unspawning don't pin world resources; the world reclaims them on a fixed budget.
