package dev.gvart.genesara.api.internal.rest.worlds

import dev.gvart.genesara.world.MaybeSet
import tools.jackson.core.JsonParser
import tools.jackson.databind.BeanProperty
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.JavaType
import tools.jackson.databind.ValueDeserializer
import tools.jackson.databind.deser.std.StdDeserializer

/**
 * Tri-state PATCH deserializer for [MaybeSet]:
 *   - JSON key absent → field keeps its Kotlin default of [MaybeSet.Skip]
 *   - JSON key null   → [MaybeSet.Set] with `value = null`
 *   - JSON key value  → [MaybeSet.Set] with the parsed inner value
 */
internal class MaybeSetDeserializer(
    private val innerType: JavaType? = null,
) : StdDeserializer<MaybeSet<Any?>>(MaybeSet::class.java) {

    override fun createContextual(ctxt: DeserializationContext, property: BeanProperty?): ValueDeserializer<*> {
        val wrapper = property?.type ?: ctxt.contextualType
        val inner = wrapper?.containedType(0) ?: ctxt.constructType(Any::class.java)
        return MaybeSetDeserializer(inner)
    }

    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): MaybeSet<Any?> {
        val type = innerType
            ?: error("MaybeSetDeserializer was not contextualised — Jackson must call createContextual first")
        return MaybeSet.Set(ctxt.readValue<Any?>(p, type))
    }

    override fun getNullValue(ctxt: DeserializationContext): MaybeSet<Any?> = MaybeSet.Set(null)
}