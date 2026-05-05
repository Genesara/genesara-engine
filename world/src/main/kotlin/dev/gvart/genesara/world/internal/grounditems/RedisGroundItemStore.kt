package dev.gvart.genesara.world.internal.grounditems

import dev.gvart.genesara.world.DroppedItemView
import dev.gvart.genesara.world.GroundItemStore
import dev.gvart.genesara.world.GroundItemView
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.Rarity
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * Redis-only [GroundItemStore]. Drops live in a per-node Hash:
 *
 * ```
 * Key:   world:groundItems:{nodeId}
 * Hash:  {dropId}  →  JSON payload (kind, item, quantity OR equipment metadata,
 *                     droppedAtTick)
 * ```
 *
 * **Atomicity.** [take] uses a Lua `HGET` + `HDEL` so two agents racing to pick
 * up the same drop on the same tick don't both succeed — only the first caller
 * gets the payload, the second sees null.
 *
 * **Persistence.** No durable backing. A Redis flush or container restart
 * vanishes every in-flight drop — that's intentional: ground items are
 * ephemeral by design.
 */
@Component
internal class RedisGroundItemStore(
    private val redis: StringRedisTemplate,
    private val mapper: ObjectMapper,
) : GroundItemStore {

    private val hash get() = redis.opsForHash<String, String>()

    override fun deposit(node: NodeId, drop: DroppedItemView, droppedAtTick: Long) {
        val payload = mapper.writeValueAsString(drop.toRecord(droppedAtTick))
        check(hash.putIfAbsent(nodeKey(node), drop.dropId.toString(), payload)) {
            "ground item ${drop.dropId} already exists at node ${node.value}"
        }
    }

    override fun atNode(node: NodeId): List<GroundItemView> {
        val raw = hash.entries(nodeKey(node))
        if (raw.isEmpty()) return emptyList()
        return raw.entries.map { (_, payload) -> mapper.readValue(payload, GroundItemRecord::class.java).toView(node) }
    }

    override fun take(node: NodeId, dropId: UUID): GroundItemView? {
        val payload = redis.execute(
            TAKE_SCRIPT,
            listOf(nodeKey(node)),
            dropId.toString(),
        ) ?: return null
        return mapper.readValue(payload, GroundItemRecord::class.java).toView(node)
    }

    private fun nodeKey(node: NodeId) = "world:groundItems:${node.value}"

    private fun DroppedItemView.toRecord(droppedAtTick: Long): GroundItemRecord = when (this) {
        is DroppedItemView.Stackable -> GroundItemRecord(
            dropId = dropId.toString(),
            droppedAtTick = droppedAtTick,
            kind = KIND_STACKABLE,
            item = item.value,
            quantity = quantity,
        )
        is DroppedItemView.Equipment -> GroundItemRecord(
            dropId = dropId.toString(),
            droppedAtTick = droppedAtTick,
            kind = KIND_EQUIPMENT,
            item = item.value,
            equipmentInstanceId = instanceId.toString(),
            equipmentRarity = rarity.name,
            equipmentDurabilityCurrent = durabilityCurrent,
            equipmentDurabilityMax = durabilityMax,
            equipmentCreatorAgentId = creatorAgentId?.toString(),
            equipmentCreatedAtTick = createdAtTick,
        )
    }

    private fun GroundItemRecord.toView(node: NodeId): GroundItemView {
        val drop: DroppedItemView = when (kind) {
            KIND_STACKABLE -> DroppedItemView.Stackable(
                dropId = UUID.fromString(dropId),
                item = ItemId(item),
                quantity = checkNotNull(quantity) { "STACKABLE record missing quantity" },
            )
            KIND_EQUIPMENT -> DroppedItemView.Equipment(
                dropId = UUID.fromString(dropId),
                item = ItemId(item),
                instanceId = UUID.fromString(checkNotNull(equipmentInstanceId)),
                rarity = Rarity.valueOf(checkNotNull(equipmentRarity)),
                durabilityCurrent = checkNotNull(equipmentDurabilityCurrent),
                durabilityMax = checkNotNull(equipmentDurabilityMax),
                creatorAgentId = equipmentCreatorAgentId?.let(UUID::fromString),
                createdAtTick = checkNotNull(equipmentCreatedAtTick),
            )
            else -> error("unknown ground item kind: $kind")
        }
        return GroundItemView(nodeId = node, droppedAtTick = droppedAtTick, drop = drop)
    }

    /**
     * Flat record used as the Redis Hash field value (JSON-encoded). One shape
     * for both stackable and equipment drops; the [kind] discriminator selects
     * which optional columns are populated.
     */
    private data class GroundItemRecord(
        val dropId: String = "",
        val droppedAtTick: Long = 0L,
        val kind: String = "",
        val item: String = "",
        val quantity: Int? = null,
        val equipmentInstanceId: String? = null,
        val equipmentRarity: String? = null,
        val equipmentDurabilityCurrent: Int? = null,
        val equipmentDurabilityMax: Int? = null,
        val equipmentCreatorAgentId: String? = null,
        val equipmentCreatedAtTick: Long? = null,
    )

    private companion object {
        private const val KIND_STACKABLE = "STACKABLE"
        private const val KIND_EQUIPMENT = "EQUIPMENT"

        /**
         * Atomic check-and-take. Returns the JSON payload as a bulk string, or
         * Lua nil if no field exists (Spring maps the latter to Java null).
         *
         *   KEYS[1] = node hash key
         *   ARGV[1] = drop id field name
         */
        private val TAKE_SCRIPT = DefaultRedisScript<String>(
            """
            local v = redis.call('HGET', KEYS[1], ARGV[1])
            if not v then return nil end
            redis.call('HDEL', KEYS[1], ARGV[1])
            return v
            """.trimIndent(),
            String::class.java,
        )
    }
}
