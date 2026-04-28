package dev.gvart.genesara.player.internal.balance

import dev.gvart.genesara.player.AgentClass
import dev.gvart.genesara.player.ClassPropertiesLookup
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
