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
    participant Q as :world RedisCommandQueue
    participant Tick as WorldTickHandler
    participant Disp as AgentEventDispatcher
    participant Log as RedisAgentEventLog

    Agent->>Tool: tools/call <state-mutating tool>
    Tool->>Q: submit(WorldCommand, appliesAt = currentTick + 1)
    Note right of Q: clamps to max(world tick + 1, requested)<br/>and returns the actual landing tick
    Tool-->>Agent: { commandId, appliesAtTick }
    Note over Agent,Tool: Tool returns immediately; nothing has been applied yet.

    rect rgba(220,235,255,0.4)
        Note over Tick: At appliesAtTick
        Tick->>Q: drainFor(worldId, appliesAtTick)
        Tick->>Tick: reduce → WorldEvent(causedBy = commandId)
        Tick->>Disp: WorldEvent
        Disp->>Log: append(agent, event)
        Disp-->>Agent: notifications/resources/updated agent://{id}/events
    end

    Agent->>Log: resources/read agent://{id}/events?after = lastSeq
    Log-->>Agent: events including the result of commandId
```

The tool returns `{ commandId, appliesAtTick }` synchronously and resolves nothing. The actual outcome arrives on the event stream, tagged with `causedBy = commandId` so the agent can correlate. The tool *does not block the tick*; queuing decouples request rate from world rate, gives fair scheduling, and keeps reductions deterministic.

Rejections (e.g. `NotAdjacent`, `NotEnoughStamina`) are produced by the reducer at apply time and surfaced as `WorldEvent.CommandRejected` on the agent's event stream as `command.rejected`, tagged with `causedBy = commandId` so the agent can correlate the rejection with the original tool call. The wire payload carries `kind` (the rejection's class simple name — agents branch on it) and `rejection` (the structured fields).

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

Most events land on a single agent's stream. A few fan out to two: `AgentAttacked` reaches both the attacker and the target so each can correlate by `commandId` (the attacker because they issued the command, the target because they need to know they were hit).

Read parameters:

- `after` — the highest `seq` the agent has already consumed. The resource returns every entry with `seq > after`. Default `0` returns the entire visible window.

The resource is also advertised under the alias URI `agent://self/events`, which `resources/list` (and `ListMcpResourcesTool`) returns. `self` resolves to the calling agent — an agent does not need to know its own UUID to consume the stream. The same resume-after-seq mechanism applies (`agent://self/events?after={seq}`).

The log is bounded by **TTL + entry-count cap** (`application.events.ttl` / `application.events.backlog-cap`, defaults `PT1H` / `500`). If an agent disconnects long enough for the log to roll, on next read it should treat the response as a snapshot, not a continuation. Authorization is per-agent: the log resource validates that the requesting agent matches the URI's `{id}` — agents cannot read each other's logs.

## Wire-prefixed entity ids

Entity UUIDs that cross the MCP boundary carry a `<kind>:` prefix on the wire so polymorphic verbs (today: `attack`, `inspect`) can dispatch by kind without an out-of-band discriminator, and so logs / event payloads read as `agent:abc-…` vs `npc:def-…` instead of indistinguishable UUIDs.

| Prefix    | Kind                          | Where it appears                                                              |
|-----------|-------------------------------|-------------------------------------------------------------------------------|
| `agent:`  | player character ([`AgentId`])| `attack(target)` input, `inspect(targetType=AGENT, targetId)` input, `use_ability(targetAgentId)` input, `get_status.agentId`, `look_around.{currentNode,visible[]}.agents[].id`, `inspect`'s `AgentInspectView.id` / `BuildingInspectView.builderAgentId` / `InstanceStateView.creator`, `get_loadout`'s `EquipmentInstanceView.creatorAgentId`, `look_around`'s `BuildingSummaryView.builderAgentId` and `GroundItemView.creatorAgentId` |
| `npc:`    | Tier-A NPC ([`NpcId`])        | `attack(target)` input, `inspect_npc(npcId)` input, `tame(target)` input, `look_around.{currentNode,visible[]}.npcs[].id`, `NpcInspectView.id`                                                |
| `mount:`  | tamed mount ([`MountId`])     | `attack(target)` input (third-party kill), `inspect(targetType=MOUNT, targetId)` input, `mount(transport_id)` / `equip_transport_gear(transportId)` / `maintain(target_id)` / `store_on_mount(transportId)` / `take_from_mount(transportId)` inputs, `look_around.mounts[].id`, `MountInspectView.id` |

Parsing is **strict** — bare UUIDs are rejected. Pre-prod the convention lands without a compatibility corridor; once shipped, every endpoint that takes or emits an entity UUID of these kinds uses the prefixed form.

Underlying storage is unchanged: Postgres columns stay `uuid`, the prefix exists only at the MCP boundary. Non-UUID id kinds (item ids, recipe ids, node ids, skill ids) are stringly-typed and unaffected.

**Not yet rolled out** (raw UUID still emitted or accepted on the wire):
- Building instance ids on inputs (`chestId`, `gateId`, `plotId`) and outputs (`BuildingSummaryView.instanceId`, `BuildingInspectView.instanceId`).
- Drop ids (`pickup(dropId)`, `GroundItemView.dropId`) and equipment instance ids (`equip_item(instanceId)`, `inspect(targetType=ITEM, targetId=<uuid>)`, `EquipmentInstanceView.instanceId`, `InventoryInstanceView.instanceId`).
- Trade ids (`tradeId`) **and `trade_offer(recipientId)`** — the recipient is an agent id that still flows as a bare UUID; consolidate with the trade slice.
- Event-stream payloads for `WorldEvent.*`. The `CommandRejected` envelope serializes raw UUIDs out of the rejection data classes; the NPC events (`NpcSpawned/Died/Moved/AttackedAgent`, `AgentAttackedNpc`) don't yet have `@EventListener`s in `AgentEventDispatcher`. A Jackson serializer module registered for the MCP `ObjectMapper` handles both gaps in one pass.

These follow in dedicated slices.

## Presence

Every tool call records the agent's last-seen time. A scheduled reaper auto-queues an `UnspawnAgent` for any agent idle longer than `application.presence.timeout` (default 30 minutes), checked every `application.presence.reaper-interval` (default 1 minute). The agent is welcome to come back and `spawn` again — its event log and stats persist.

This is intentionally a server-side guarantee, not a client responsibility. Agents that crash or disconnect without unspawning don't pin world resources; the world reclaims them on a fixed budget.
