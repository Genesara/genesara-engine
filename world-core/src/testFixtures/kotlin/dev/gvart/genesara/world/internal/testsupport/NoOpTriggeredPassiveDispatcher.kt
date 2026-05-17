package dev.gvart.genesara.world.internal.testsupport

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.TriggeredPassiveTrigger
import dev.gvart.genesara.world.events.WorldEvent
import dev.gvart.genesara.world.internal.perks.TriggerContext
import dev.gvart.genesara.world.internal.perks.TriggeredPassiveDispatcher
import java.util.UUID

/** Default for reducer tests that don't exercise the perk surface. */
internal object NoOpTriggeredPassiveDispatcher : TriggeredPassiveDispatcher {
    override fun dispatch(
        firer: AgentId,
        trigger: TriggeredPassiveTrigger,
        ctx: TriggerContext,
        tick: Long,
        causedBy: UUID?,
    ): List<WorldEvent> = emptyList()
}
