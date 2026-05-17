package dev.gvart.genesara.world.internal.tick

import dev.gvart.genesara.world.WorldCommandGateway
import dev.gvart.genesara.world.WorldId
import dev.gvart.genesara.world.commands.WorldCommand
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * Drain side of the Redis command queue. Owns the per-(world, tick) DEL so
 * `WorldTickHandler` can pull every command for its tick atomically without
 * worrying about a sibling tick on the same pod walking the same key.
 */
internal interface WorldCommandDrainer {
    fun drainFor(worldId: WorldId, tick: Long): List<WorldCommand>
}

/**
 * Per-world Redis-backed command queue. Keys: `world:{w}:queue:{tick}` —
 * one Redis list per (world, tick) pair. Submitting agents push; the lease
 * holder for that world drains.
 *
 * **Submit guard.** Before pushing, we read `world:{w}:tick` and clamp the
 * target tick to `max(current+1, requestedAppliesAt)`. The return value is
 * the actual landing tick, which the MCP-tool surfaces back to the agent —
 * routine handover is invisible because the agent expects the event at the
 * tick the gateway reports, not the optimistic one the tool computed from
 * its global `TickClock` hint.
 *
 * **Residual race — silent loss.** Between the `currentTick` read and the
 * `LPUSH` it is theoretically possible for the lease holder to bump the
 * world to the computed target tick and drain it before the push lands. The
 * orphaned command is silently lost: the agent already received an ack for
 * a tick the world has moved past, so they have to detect the absence of a
 * matching `causedBy = commandId` event by `appliesAtTick + slack` and
 * re-issue. Closing the race would require folding the GET into the LPUSH
 * via Lua; the window is microseconds and the cost wasn't worth carrying
 * yet.
 *
 * **Order.** `LPUSH` puts new entries at the head; `LRANGE 0 -1` returns
 * head-first (newest first). The drainer `.reversed()` after deserializing
 * so reducers see commands in submission order — a property the in-process
 * `ConcurrentLinkedQueue` predecessor preserved by accident and that some
 * cross-agent reducer ordering (e.g. simultaneous pickup of the same drop)
 * actually depends on.
 */
@Component
internal class RedisCommandQueue(
    private val redis: StringRedisTemplate,
    private val mapper: ObjectMapper,
    private val tickCounter: WorldTickCounter,
    private val router: AgentWorldRouter,
) : WorldCommandGateway, WorldCommandDrainer {

    override fun submit(command: WorldCommand, appliesAtTick: Long): Long {
        val worldId = router.routeFor(command.agent)
            ?: error("No worlds configured: cannot route ${command::class.simpleName} for agent ${command.agent.id}")
        val current = tickCounter.currentTick(worldId)
        val targetTick = maxOf(current + 1, appliesAtTick)
        val payload = mapper.writeValueAsString(command)
        redis.opsForList().leftPush(queueKey(worldId, targetTick), payload)
        return targetTick
    }

    override fun drainFor(worldId: WorldId, tick: Long): List<WorldCommand> {
        val raw = redis.execute(DRAIN_SCRIPT, listOf(queueKey(worldId, tick))) ?: return emptyList()
        val payloads = raw.filterIsInstance<String>()
        if (payloads.isEmpty()) return emptyList()
        return payloads.asReversed().map { mapper.readValue(it, WorldCommand::class.java) }
    }

    private fun queueKey(worldId: WorldId, tick: Long) = "world:${worldId.value}:queue:$tick"

    private companion object {
        private val DRAIN_SCRIPT: DefaultRedisScript<List<*>> = DefaultRedisScript(
            """
            local items = redis.call('LRANGE', KEYS[1], 0, -1)
            if #items == 0 then return {} end
            redis.call('DEL', KEYS[1])
            return items
            """.trimIndent(),
            List::class.java,
        )
    }
}
