package dev.gvart.genesara.world.internal.equipment

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.TriggeredPassiveTrigger
import dev.gvart.genesara.world.ActiveSetTrigger
import dev.gvart.genesara.world.AgentItemInstancesStore
import dev.gvart.genesara.world.EquipmentSet
import dev.gvart.genesara.world.EquipmentSetLookup
import dev.gvart.genesara.world.EquipmentSetTriggerLookup
import org.springframework.stereotype.Component

@Component
internal class EquipmentSetTriggerLookupImpl(
    private val equipment: AgentItemInstancesStore,
    private val sets: EquipmentSetLookup,
) : EquipmentSetTriggerLookup {

    override fun matching(agent: AgentId, trigger: TriggeredPassiveTrigger): List<ActiveSetTrigger> {
        val equipped = equipment.equippedFor(agent)
        if (equipped.isEmpty()) return emptyList()

        val countsPerSet = mutableMapOf<EquipmentSet, Int>()
        for ((_, instance) in equipped) {
            for (set in sets.setsContaining(instance.itemId)) {
                countsPerSet[set] = (countsPerSet[set] ?: 0) + 1
            }
        }
        if (countsPerSet.isEmpty()) return emptyList()

        val matches = mutableListOf<ActiveSetTrigger>()
        for ((set, count) in countsPerSet) {
            for (active in set.activeTriggeredPassives(count)) {
                if (active.effect.trigger != trigger) continue
                matches += ActiveSetTrigger(
                    setId = set.id,
                    tier = active.tier,
                    indexInTier = active.indexInTier,
                    effect = active.effect,
                )
            }
        }
        return matches
    }
}
