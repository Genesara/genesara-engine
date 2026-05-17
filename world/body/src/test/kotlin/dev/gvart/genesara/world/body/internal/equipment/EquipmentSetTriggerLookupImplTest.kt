package dev.gvart.genesara.world.body.internal.equipment

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.PerkEffect
import dev.gvart.genesara.player.TriggeredPassiveEffectKind
import dev.gvart.genesara.player.TriggeredPassiveTrigger
import dev.gvart.genesara.world.EquipSlot
import dev.gvart.genesara.world.EquippedBonus
import dev.gvart.genesara.world.DamageType
import dev.gvart.genesara.world.ItemInstance
import dev.gvart.genesara.world.AgentItemInstancesStore
import dev.gvart.genesara.world.EquipmentSet
import dev.gvart.genesara.world.EquipmentSetId
import dev.gvart.genesara.world.EquipmentSetLookup
import dev.gvart.genesara.world.EquipmentSetThreshold
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.Rarity
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EquipmentSetTriggerLookupImplTest {

    private val agent = AgentId(UUID.randomUUID())
    private val ironHelm = ItemId("IRON_HELM")
    private val ironChest = ItemId("IRON_CHEST")
    private val ironBoots = ItemId("IRON_BOOTS")

    private val reflect = PerkEffect.TriggeredPassive(
        trigger = TriggeredPassiveTrigger.ON_HIT_TAKEN,
        effectKind = TriggeredPassiveEffectKind.HEAL_SELF,
        params = mapOf("multiplierPct" to "10"),
        internalCooldownTicks = 5,
    )
    private val onHitDealt = PerkEffect.TriggeredPassive(
        trigger = TriggeredPassiveTrigger.ON_HIT_DEALT,
        effectKind = TriggeredPassiveEffectKind.GRANT_SELF_BUFF,
        params = emptyMap(),
        internalCooldownTicks = 3,
    )
    private val ironSet = EquipmentSet(
        id = EquipmentSetId("IRON"),
        pieces = setOf(ironHelm, ironChest, ironBoots),
        thresholds = mapOf(
            2 to EquipmentSetThreshold(
                bonuses = listOf(EquippedBonus.ArmorDef(DamageType.SLASH, 1)),
                triggeredPassives = listOf(reflect),
            ),
            3 to EquipmentSetThreshold(
                bonuses = listOf(EquippedBonus.ArmorDef(DamageType.SLASH, 1)),
                triggeredPassives = listOf(onHitDealt),
            ),
        ),
    )

    @Test
    fun `no equipped pieces yields no triggers`() {
        val lookup = EquipmentSetTriggerLookupImpl(EmptyStore, SetLookupOf(ironSet))
        assertTrue(lookup.matching(agent, TriggeredPassiveTrigger.ON_HIT_TAKEN).isEmpty())
    }

    @Test
    fun `equipping below the smallest threshold yields no triggers`() {
        val store = SingleAgentStore(agent, mapOf(EquipSlot.HELMET to instance(ironHelm)))
        val lookup = EquipmentSetTriggerLookupImpl(store, SetLookupOf(ironSet))

        assertTrue(lookup.matching(agent, TriggeredPassiveTrigger.ON_HIT_TAKEN).isEmpty())
    }

    @Test
    fun `2-piece equipped fires the 2-piece trigger only`() {
        val store = SingleAgentStore(agent, mapOf(
            EquipSlot.HELMET to instance(ironHelm),
            EquipSlot.CHEST to instance(ironChest),
        ))
        val lookup = EquipmentSetTriggerLookupImpl(store, SetLookupOf(ironSet))

        val active = lookup.matching(agent, TriggeredPassiveTrigger.ON_HIT_TAKEN)
        assertEquals(1, active.size)
        assertEquals(2, active.single().tier)
        assertEquals("set:IRON@2:0", active.single().syntheticPerkId)
    }

    @Test
    fun `3-piece equipped exposes both tier triggers, each on its own trigger key`() {
        val store = SingleAgentStore(agent, mapOf(
            EquipSlot.HELMET to instance(ironHelm),
            EquipSlot.CHEST to instance(ironChest),
            EquipSlot.BOOTS to instance(ironBoots),
        ))
        val lookup = EquipmentSetTriggerLookupImpl(store, SetLookupOf(ironSet))

        val onHit = lookup.matching(agent, TriggeredPassiveTrigger.ON_HIT_TAKEN)
        val onDealt = lookup.matching(agent, TriggeredPassiveTrigger.ON_HIT_DEALT)
        assertEquals(listOf(2), onHit.map { it.tier })
        assertEquals(listOf(3), onDealt.map { it.tier })
    }

    @Test
    fun `mismatched trigger returns no entries`() {
        val store = SingleAgentStore(agent, mapOf(
            EquipSlot.HELMET to instance(ironHelm),
            EquipSlot.CHEST to instance(ironChest),
        ))
        val lookup = EquipmentSetTriggerLookupImpl(store, SetLookupOf(ironSet))

        assertTrue(lookup.matching(agent, TriggeredPassiveTrigger.ON_CRIT).isEmpty())
    }

    private fun instance(itemId: ItemId, rarity: Rarity = Rarity.COMMON): ItemInstance.Equipment = ItemInstance.Equipment(
        instanceId = UUID.randomUUID(),
        agentId = agent,
        itemId = itemId,
        rarity = rarity,
        durabilityCurrent = 100,
        durabilityMax = 100,
        creatorAgentId = agent,
        createdAtTick = 0L,
        equippedInSlot = EquipSlot.HELMET,
    )

    private object EmptyStore : dev.gvart.genesara.world.internal.testsupport.InMemoryAgentItemInstancesStore() {
        override fun equippedFor(agentId: AgentId): Map<EquipSlot, ItemInstance.Equipment> = emptyMap()
        override fun assignToSlot(instanceId: UUID, agentId: AgentId, slot: EquipSlot): ItemInstance.Equipment? = error("not used")
        override fun clearSlot(agentId: AgentId, slot: EquipSlot): ItemInstance.Equipment? = error("not used")
        override fun decrementDurability(instanceId: UUID, amount: Int): ItemInstance.Equipment? = error("not used")
        override fun delete(instanceId: UUID): Boolean = error("not used")
    }

    private class SingleAgentStore(
        private val target: AgentId,
        private val equipped: Map<EquipSlot, ItemInstance.Equipment>,
    ) : dev.gvart.genesara.world.internal.testsupport.InMemoryAgentItemInstancesStore() {
        override fun equippedFor(agentId: AgentId): Map<EquipSlot, ItemInstance.Equipment> =
            if (agentId == target) equipped else emptyMap()
        override fun assignToSlot(instanceId: UUID, agentId: AgentId, slot: EquipSlot): ItemInstance.Equipment? = error("not used")
        override fun clearSlot(agentId: AgentId, slot: EquipSlot): ItemInstance.Equipment? = error("not used")
        override fun decrementDurability(instanceId: UUID, amount: Int): ItemInstance.Equipment? = error("not used")
        override fun delete(instanceId: UUID): Boolean = error("not used")
    }

    private class SetLookupOf(vararg sets: EquipmentSet) : EquipmentSetLookup {
        private val all = sets.toList()
        override fun byId(id: EquipmentSetId): EquipmentSet? = all.firstOrNull { it.id == id }
        override fun all(): List<EquipmentSet> = all
        override fun setsContaining(itemId: ItemId): List<EquipmentSet> =
            all.filter { itemId in it.pieces }
    }
}
