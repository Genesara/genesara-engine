package dev.gvart.agenticrpg.player.internal.balance

import dev.gvart.agenticrpg.player.AgentClass
import dev.gvart.agenticrpg.player.ClassPropertiesLookup
import org.springframework.stereotype.Component

@Component
internal class ClassDefinitionLookup(
    private val props: ClassDefinitionProperties,
) : ClassPropertiesLookup {

    override fun sightRange(classId: AgentClass?): Int =
        propertiesFor(classId).sightRange

    private fun propertiesFor(classId: AgentClass?): ClassProperties =
        classId?.let { props.classes[it] } ?: props.default
}
