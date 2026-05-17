package dev.gvart.genesara.world.body.internal.equipment

import dev.gvart.genesara.player.TriggeredPassiveEffectKind
import dev.gvart.genesara.player.TriggeredPassiveTrigger

internal data class EquipmentSetProperties(
    val pieces: List<String>,
    /** Map<tierCount, threshold> — tier 2/4/6/etc → bonuses added at that tier. */
    val thresholds: Map<Int, EquipmentSetThresholdProperties>,
)

internal data class EquipmentSetThresholdProperties(
    val bonuses: List<dev.gvart.genesara.world.internal.balance.EquippedBonusProperties> = emptyList(),
    /**
     * Optional triggered passives that arm when this threshold is active. Re-uses
     * the [TriggeredPassiveTrigger] / [TriggeredPassiveEffectKind] enums from the
     * perk system; the dispatcher composes set-bonus triggers alongside chosen
     * perks via a synthetic perk id keyed `"set:<setId>@<tier>:<index>"`.
     */
    val triggeredPassives: List<EquipmentSetTriggeredPassiveProperties> = emptyList(),
)

internal data class EquipmentSetTriggeredPassiveProperties(
    val trigger: TriggeredPassiveTrigger,
    val effectKind: TriggeredPassiveEffectKind,
    val params: Map<String, String> = emptyMap(),
    val internalCooldownTicks: Int = 0,
)
