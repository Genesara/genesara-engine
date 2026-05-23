# Genesara dashboard API — FE integration brief

> Spectator UI for a server-driven MMORPG. Backend runs at `http://localhost:8080` in dev (CORS allow-list already includes `http://localhost:5173`). All responses are JSON; errors are RFC 7807 `application/problem+json`.

## Auth model

Two tokens, two purposes — don't mix them:

| Token | Where it comes from | Where it goes | Used by |
|---|---|---|---|
| `token` (JWT) | `POST /api/players` or `POST /api/players/login` | `Authorization: Bearer <token>` on `/api/agents/**`, `/api/me/**` | The dashboard FE |
| `apiToken` (opaque) | `POST /api/players` (in `apiToken`) or `GET /api/me/api-token` | MCP / agent runtime endpoints (not the dashboard) | Game agents |

JWT TTL is **24h**. There's no refresh endpoint — re-login on 401. Persist `token` in memory + `sessionStorage`; never `localStorage` (XSS).

## Base URL

```
VITE_API_BASE=http://localhost:8080
```

All paths below are relative to `${VITE_API_BASE}`.

## Endpoint reference

### Public (no auth)

| Method | Path | Response |
|---|---|---|
| `POST` | `/api/players` | `{ playerId, apiToken, token }` (201) — body `{ username, password }`, username 3–64, password 8–256 |
| `POST` | `/api/players/login` | `{ token }` — body `{ username, password }` |
| `GET`  | `/api/stats` | `{ tick, tickIntervalMs, onlineAgents, totalAgents }` — safe to poll every 1–2s |

### Player JWT (`Authorization: Bearer <token>`)

| Method | Path | Response |
|---|---|---|
| `GET`  | `/api/me/api-token` | `{ apiToken }` |
| `POST` | `/api/me/api-token/rotate` | `{ apiToken }` — invalidates the previous one |
| `GET`  | `/api/agents` | `AgentSummary[]` — list of owned agents |
| `POST` | `/api/agents` | `{ agentId }` (201) — body `{ name }` |
| `DELETE` | `/api/agents/{agentId}` | 204 |
| `GET`  | `/api/agents/{agentId}` | `AgentDetail` — cheap; poll at 1–2s |
| `GET`  | `/api/agents/{agentId}/skills` | `Skills` — split off the detail call; fetch on tab open |
| `GET`  | `/api/agents/{agentId}/inventory` | `{ entries: InventoryEntry[] }` — stackables only |
| `GET`  | `/api/agents/{agentId}/loadout` | `Loadout` — stackables + equipment + key instances |
| `GET`  | `/api/agents/{agentId}/map` | `{ nodes: RecalledNode[] }` — agent memory only, not the full world |
| `GET`  | `/api/agents/{agentId}/relationships` | `{ authority, fame, entries: RelationshipEntry[] }` |
| `GET`  | `/api/agents/{agentId}/events?after=<seq>&limit=<n>` | `AgentEvent[]` — `seq` monotonic, log TTL is 1h / 500 entries |
| `GET`  | `/api/agents/{agentId}/events/stream?after=<seq>` | `text/event-stream` (SSE) |

A 404 on agent sub-resources means **"not yours or doesn't exist"** — both cases are merged on purpose. Do not display "permission denied" copy.

## Error envelope

Every error is RFC 7807:

```json
{ "type": "about:blank", "title": "Not Found", "status": 404, "detail": "Agent not found", "instance": "/api/agents/..." }
```

Validation failures (400) carry a `errors` array of field violations. Read `status` for branching, `detail` for toast text.

## SSE: live event tail

`GET /api/agents/{agentId}/events/stream?after=<seq>` returns an SSE stream of per-agent events. The log is the source of truth — the stream is a **wake-up hint**. The server backfills any gap on connect by reading `after=` against the log.

Pattern (works with the native `EventSource` API, but `EventSource` does not support `Authorization` headers — use `@microsoft/fetch-event-source` or equivalent):

```ts
import { fetchEventSource } from "@microsoft/fetch-event-source";

let lastSeq = 0;

await fetchEventSource(
  `${BASE}/api/agents/${agentId}/events/stream?after=${lastSeq}`,
  {
    headers: { Authorization: `Bearer ${jwt}` },
    onmessage(ev) {
      if (!ev.data) return;             // comment frames are heartbeats
      const event = JSON.parse(ev.data) as AgentEvent;
      lastSeq = event.seq;
      dispatch(event);
    },
    onerror(err) {
      // throw to stop, return number-of-ms to retry
      return 2000;
    },
  }
);
```

Server sends `:ping\n\n` comments every 30s to keep proxies alive — `fetch-event-source` silently absorbs them. On reconnect, replay with the last `seq` you saw; the server fills the gap from the ring buffer (bounded by 1h / 500 entries).

Event shape — `payload` is untyped on purpose, but `type` is a stable string discriminator (`agent.moved`, `agent.spawned`, etc):

```ts
type AgentEvent = {
  id: string;          // UUID
  seq: number;         // monotonic per agent
  type: string;        // "agent.moved", "agent.spawned", ...
  tick: number;        // server tick at which it happened
  payload: unknown;    // raw world event JSON
};
```

## Polling cadence

Each agent-detail screen should run **one SSE connection** for activity and **two pollers**:

| Source | Interval | Why |
|---|---|---|
| `/api/stats` | 1–2s | World tick is 1s; stat numbers are cheap |
| `/api/agents/{id}` | 1–2s | Gauges (HP/stamina/hunger/etc) drift continuously |
| `/api/agents/{id}/skills`, `/inventory`, `/loadout`, `/map`, `/relationships` | on demand or on relevant SSE event | Don't poll these on a fixed timer |

When a relevant SSE event arrives (e.g. `inventory.changed`), invalidate the matching cache key — don't try to merge payloads into local state.

## TypeScript types (mirror of server views)

```ts
// ── Enums ────────────────────────────────────────────────────────────────────

export type Rarity = "COMMON" | "UNCOMMON" | "RARE" | "EPIC" | "LEGENDARY";

export type EquipSlot =
  | "HELMET" | "CHEST" | "PANTS" | "BOOTS" | "GLOVES"
  | "AMULET" | "RING_LEFT" | "RING_RIGHT"
  | "BRACELET_LEFT" | "BRACELET_RIGHT"
  | "MAIN_HAND" | "OFF_HAND";

export type ItemCategory = "RESOURCE" | "EQUIPMENT" | "KEY";

export type SkillCategory =
  | "GATHERING" | "CRAFTING" | "COMBAT" | "ATHLETICS" | "SURVIVAL"
  | "KNOWLEDGE" | "STEALTH" | "SOCIAL" | "ANIMAL" | "CLASS_LOCKED";

export type AgentClass =
  | "SOLDIER" | "HEAVY_SOLDIER" | "STEALTH_SOLDIER" | "COMMANDER"
  | "SCOUT"   | "RANGER"        | "SNIPER"          | "PATHFINDER"
  | "HUNTER"  | "BEASTMASTER"   | "TRAPPER"         | "POACHER"
  | "ARTISAN" | "SMITH"         | "CHEF"            | "JEWELER"
  | "ENGINEER"| "TECHNICIAN"    | "ARTILLERIST"     | "ARCHITECT"
  | "MEDIC"   | "SURGEON"       | "APOTHECARY"      | "FIELD_MEDIC"
  | "MERCHANT"| "NEGOTIATOR"    | "SMUGGLER"        | "CARAVAN_MASTER"
  | "RESEARCHER"|"SCHOLAR"      | "ALCHEMIST"       | "NATURALIST";

export type Terrain =
  | "FOREST" | "BIRCH_FOREST" | "RAINFOREST" | "PLAINS" | "MEADOW" | "HILLS"
  | "DESERT" | "SALT_FLATS" | "ICE_TUNDRA" | "GLACIER" | "VOLCANIC"
  | "COASTAL" | "RIVER_DELTA" | "WETLANDS" | "SWAMP" | "OCEAN"
  | "MOUNTAIN" | "ALPINE" | "CLIFFSIDE" | "CANYON"
  | "ANCIENT_RUINS" | "CURSED_LAND" | "SACRED_GROVE" | "CRYSTAL_CAVES" | "BLIGHTED"
  | "FOREST_EDGE" | "FOOTHILLS" | "SHORELINE"
  | "DIRT_PATH" | "GRAVEL_ROAD" | "WOODEN_BRIDGE" | "STONE_BRIDGE" | "TRADE_ROUTE";

export type Biome =
  | "FOREST" | "PLAINS" | "MOUNTAIN" | "COASTAL"
  | "SWAMP"  | "RUINS"  | "DESERT"   | "TUNDRA" | "OCEAN";

// ── Stats / detail ──────────────────────────────────────────────────────────

export type Pool = { current: number; max: number };

export type PublicStats = {
  tick: number;
  tickIntervalMs: number;
  onlineAgents: number;
  totalAgents: number;
};

export type AgentSummary = {
  agentId: string;
  name: string;
  classId: AgentClass | null;
  race: string;
  level: number;
  xpCurrent: number;
  xpToNext: number;
  gauges: {
    hp: Pool; stamina: Pool; mana: Pool;
    hunger: Pool; thirst: Pool; sleep: Pool;
  } | null;
  locationNodeId: number | null;
  spawned: boolean;
  lastActiveAt: string | null; // ISO-8601
};

export type AgentDetail = {
  agentId: string;
  name: string;
  race: string;
  classId: AgentClass | null;
  level: number;
  xp: { current: number; toNext: number };
  attributes: {
    strength: number; dexterity: number; constitution: number;
    perception: number; intelligence: number; luck: number;
  };
  unspentAttributePoints: number;
  gauges: AgentSummary["gauges"];
  location: number | null;
  safeNode: number | null;
  tick: number;
  authority: number;
  fame: number;
  pendingClassChoice: AgentClass[];      // empty unless L10 offer is pending
  pendingEvolutionChoice: AgentClass[];  // empty unless L50 offer is pending
};

// ── Skills ──────────────────────────────────────────────────────────────────

export type SkillEntry = {
  id: string;
  displayName: string;
  category: SkillCategory;
  xp: number;
  level: number;
  recommendCount: number;
};

export type Skills = {
  slotCount: number;
  slotsFilled: number;
  slots: { slotIndex: number; skill: SkillEntry | null }[];
  unslotted: SkillEntry[];
  chosenPerks: { skillId: string; milestone: number; perkId: string }[];
  pendingPerkChoices: { skillId: string; milestone: number; options: string[] }[];
};

// ── Inventory / loadout ─────────────────────────────────────────────────────

export type InventoryEntry = {
  itemId: string;
  quantity: number;
  rarity: Rarity;
};

export type EquipmentInstance = {
  instanceId: string;
  itemId: string;
  category: ItemCategory;
  rarity: Rarity;
  durabilityCurrent: number;
  durabilityMax: number;
  creatorAgentId: string | null;
};

export type ItemInstance = {
  instanceId: string;
  itemId: string;
  category: ItemCategory;
  rarity: Rarity;
  gateInstanceId?: string;
};

export type Loadout = {
  stackable: InventoryEntry[];
  instances: ItemInstance[];
  equipment: {
    slots: { slotId: EquipSlot; instance: EquipmentInstance | null }[];
    stash: EquipmentInstance[];
  };
};

// ── Map / relationships / events ────────────────────────────────────────────

export type RecalledNode = {
  nodeId: number;
  regionId: number;
  q: number;
  r: number;
  terrain: Terrain;
  biome: Biome | null;
  firstSeenTick: number;
  lastSeenTick: number;
};

export type Relationships = {
  authority: number;
  fame: number;
  entries: {
    agentId: string;
    agentName: string | null;
    score: number;
    lastChangedAtTick: number;
  }[];
};

export type AgentEvent = {
  id: string;
  seq: number;
  type: string;
  tick: number;
  payload: unknown;
};

// ── ProblemDetail ───────────────────────────────────────────────────────────

export type ProblemDetail = {
  type: string;
  title: string;
  status: number;
  detail?: string;
  instance?: string;
  errors?: { field: string; message: string }[];
};
```

## Minimal fetch client

```ts
export class ApiError extends Error {
  constructor(public problem: ProblemDetail) { super(problem.detail ?? problem.title); }
}

export function makeClient(getToken: () => string | null) {
  const base = import.meta.env.VITE_API_BASE;

  async function call<T>(path: string, init: RequestInit = {}): Promise<T> {
    const token = getToken();
    const res = await fetch(`${base}${path}`, {
      ...init,
      headers: {
        "Content-Type": "application/json",
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
        ...init.headers,
      },
    });
    if (!res.ok) throw new ApiError(await res.json());
    if (res.status === 204) return undefined as T;
    return res.json() as Promise<T>;
  }

  return {
    register: (b: { username: string; password: string }) =>
      call<{ playerId: string; apiToken: string; token: string }>("/api/players", { method: "POST", body: JSON.stringify(b) }),
    login: (b: { username: string; password: string }) =>
      call<{ token: string }>("/api/players/login", { method: "POST", body: JSON.stringify(b) }),
    stats: () => call<PublicStats>("/api/stats"),
    listAgents: () => call<AgentSummary[]>("/api/agents"),
    createAgent: (b: { name: string }) => call<{ agentId: string }>("/api/agents", { method: "POST", body: JSON.stringify(b) }),
    deleteAgent: (id: string) => call<void>(`/api/agents/${id}`, { method: "DELETE" }),
    agent:        (id: string) => call<AgentDetail>(`/api/agents/${id}`),
    skills:       (id: string) => call<Skills>(`/api/agents/${id}/skills`),
    inventory:    (id: string) => call<{ entries: InventoryEntry[] }>(`/api/agents/${id}/inventory`),
    loadout:      (id: string) => call<Loadout>(`/api/agents/${id}/loadout`),
    map:          (id: string) => call<{ nodes: RecalledNode[] }>(`/api/agents/${id}/map`),
    relationships:(id: string) => call<Relationships>(`/api/agents/${id}/relationships`),
    eventsSince:  (id: string, after = 0, limit = 50) =>
      call<AgentEvent[]>(`/api/agents/${id}/events?after=${after}&limit=${limit}`),
  };
}
```

## Suggested FE shape

- **Routes**: `/login`, `/register`, `/agents` (list + create), `/agents/:id` (detail dashboard).
- **Detail page**: tabs for `Overview` (detail+skills), `Inventory` (loadout), `Map`, `Relationships`, `Events` (live tail).
- **Global**: a top-bar widget driven by `/api/stats` (tick + online count).
- **State**: React Query / TanStack Query — `staleTime: 1000`, `refetchInterval: 1500` for the polled keys; cache invalidation on SSE event types.

## Prompt you can paste into an FE agent

```
You are building the spectator dashboard for Genesara, an AI-driven MMORPG.
The backend is a Spring Boot service at $VITE_API_BASE. Use the attached
"Genesara dashboard API" brief as the only source of truth for endpoints,
auth, types, and error shape. Constraints:

- React 18 + Vite + TypeScript. TanStack Query for fetching. TanStack Router
  (or React Router 6) for routing. shadcn/ui + Tailwind for components.
- Use `@microsoft/fetch-event-source` for SSE — never raw EventSource (no
  Authorization header support).
- Store JWT in memory + sessionStorage; never localStorage.
- Render RFC 7807 errors as toasts; branch on `status`.
- Build the smallest viable surface first: Register → Login → Agent list →
  Agent detail (Overview tab only) → SSE event tail. Stop and confirm before
  adding tabs.
- Match server enum strings exactly; do not invent labels. Display strings
  belong in a `i18n/enums.ts` map.
- Do not poll the heavy endpoints (skills, inventory, loadout, map,
  relationships) on a timer — fetch on tab open, invalidate on the matching
  SSE event.
```
