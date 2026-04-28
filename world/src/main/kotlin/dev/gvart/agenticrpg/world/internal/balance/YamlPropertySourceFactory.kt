package dev.gvart.agenticrpg.world.internal.balance

import org.springframework.beans.factory.config.YamlPropertiesFactoryBean
import org.springframework.core.env.PropertiesPropertySource
import org.springframework.core.env.PropertySource
import org.springframework.core.io.support.EncodedResource
import org.springframework.core.io.support.PropertySourceFactory

internal class YamlPropertySourceFactory : PropertySourceFactory {
    override fun createPropertySource(name: String?, resource: EncodedResource): PropertySource<*> {
        val properties = YamlPropertiesFactoryBean()
            .apply { setResources(resource.resource) }
            .getObject() ?: error("Failed to load YAML from ${resource.resource.filename}")
        return PropertiesPropertySource(name ?: resource.resource.filename!!, properties)
    }
}