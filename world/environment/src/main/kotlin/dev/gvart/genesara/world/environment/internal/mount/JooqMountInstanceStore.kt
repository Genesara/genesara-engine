package dev.gvart.genesara.world.environment.internal.mount

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.Mount
import dev.gvart.genesara.world.MountId
import dev.gvart.genesara.world.MountInstanceStore
import dev.gvart.genesara.world.MountType
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.internal.jooq.tables.records.MountsRecord
import dev.gvart.genesara.world.internal.jooq.tables.references.MOUNTS
import org.jooq.DSLContext
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
internal class JooqMountInstanceStore(
    private val dsl: DSLContext,
) : MountInstanceStore {

    @Transactional
    override fun insert(mount: Mount) {
        dsl.insertInto(MOUNTS)
            .set(MOUNTS.MOUNT_ID, mount.id.value)
            .set(MOUNTS.MOUNT_TYPE, mount.type.value)
            .set(MOUNTS.NODE_ID, mount.nodeId.value)
            .set(MOUNTS.HP_MAX, mount.hpMax)
            .set(MOUNTS.HP_CURRENT, mount.hpCurrent)
            .set(MOUNTS.HUNGER_MAX, mount.hungerMax)
            .set(MOUNTS.HUNGER, mount.hunger)
            .set(MOUNTS.FATIGUE_MAX, mount.fatigueMax)
            .set(MOUNTS.FATIGUE, mount.fatigue)
            .set(MOUNTS.MOUNTED_BY_AGENT_ID, mount.mountedByAgentId?.id)
            .set(MOUNTS.TAMED_AT_TICK, mount.tamedAtTick)
            .set(MOUNTS.SADDLE_SPEED_BONUS, mount.saddleSpeedBonus)
            .set(MOUNTS.HARNESS_CARGO_BONUS_GRAMS, mount.harnessCargoBonusGrams)
            .set(MOUNTS.CURRENT_LOAD_GRAMS, mount.currentLoadGrams)
            .execute()
    }

    @Transactional(readOnly = true)
    override fun findById(mountId: MountId): Mount? =
        dsl.selectFrom(MOUNTS)
            .where(MOUNTS.MOUNT_ID.eq(mountId.value))
            .fetchOne(::toDomain)

    @Transactional(readOnly = true)
    override fun byNodes(nodeIds: Collection<NodeId>): List<Mount> {
        if (nodeIds.isEmpty()) return emptyList()
        return dsl.selectFrom(MOUNTS)
            .where(MOUNTS.NODE_ID.`in`(nodeIds.map { it.value }))
            .fetch(::toDomain)
    }

    @Transactional(readOnly = true)
    override fun findByRider(agentId: AgentId): Mount? =
        dsl.selectFrom(MOUNTS)
            .where(MOUNTS.MOUNTED_BY_AGENT_ID.eq(agentId.id))
            .fetchOne(::toDomain)

    @Transactional(readOnly = true)
    override fun all(): List<Mount> =
        dsl.selectFrom(MOUNTS).fetch(::toDomain)

    @Transactional
    override fun delete(mountId: MountId): Boolean =
        dsl.deleteFrom(MOUNTS)
            .where(MOUNTS.MOUNT_ID.eq(mountId.value))
            .execute() > 0

    @Transactional
    override fun update(mount: Mount): Boolean =
        dsl.update(MOUNTS)
            .set(MOUNTS.NODE_ID, mount.nodeId.value)
            .set(MOUNTS.HP_CURRENT, mount.hpCurrent)
            .set(MOUNTS.HUNGER, mount.hunger)
            .set(MOUNTS.FATIGUE, mount.fatigue)
            .set(MOUNTS.MOUNTED_BY_AGENT_ID, mount.mountedByAgentId?.id)
            .set(MOUNTS.SADDLE_SPEED_BONUS, mount.saddleSpeedBonus)
            .set(MOUNTS.HARNESS_CARGO_BONUS_GRAMS, mount.harnessCargoBonusGrams)
            .set(MOUNTS.CURRENT_LOAD_GRAMS, mount.currentLoadGrams)
            .where(MOUNTS.MOUNT_ID.eq(mount.id.value))
            .execute() > 0

    private fun toDomain(record: MountsRecord): Mount =
        Mount(
            id = MountId(record.mountId),
            type = MountType(record.mountType),
            nodeId = NodeId(record.nodeId),
            hpCurrent = record.hpCurrent,
            hpMax = record.hpMax,
            hunger = record.hunger,
            hungerMax = record.hungerMax,
            fatigue = record.fatigue,
            fatigueMax = record.fatigueMax,
            mountedByAgentId = record.mountedByAgentId?.let(::AgentId),
            tamedAtTick = record.tamedAtTick,
            saddleSpeedBonus = record.saddleSpeedBonus ?: 0,
            harnessCargoBonusGrams = record.harnessCargoBonusGrams ?: 0,
            currentLoadGrams = record.currentLoadGrams ?: 0L,
        )
}
