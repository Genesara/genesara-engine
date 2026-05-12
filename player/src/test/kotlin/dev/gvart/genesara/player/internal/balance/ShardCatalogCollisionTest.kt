package dev.gvart.genesara.player.internal.balance

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean
import org.springframework.core.io.Resource
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import kotlin.test.assertTrue

class ShardCatalogCollisionTest {

    private val resolver = PathMatchingResourcePatternResolver()

    @Test
    fun `no skill id is defined in more than one skills shard`() {
        assertNoCollisions(
            shardGlob = "classpath:player-definition/skills/*.yaml",
            idPrefix = "skills.catalog.",
        )
    }

    @Test
    fun `no class id is defined in more than one classes shard`() {
        assertNoCollisions(
            shardGlob = "classpath:player-definition/classes/*.yaml",
            idPrefix = "player.classes.",
        )
    }

    private fun assertNoCollisions(shardGlob: String, idPrefix: String) {
        val owners = mutableMapOf<String, MutableList<String>>()

        for (resource in resolver.getResources(shardGlob)) {
            val idsInFile = idsIn(resource, idPrefix)
            val filename = resource.filename ?: resource.uri.toString()
            for (id in idsInFile) {
                owners.getOrPut(id) { mutableListOf() } += filename
            }
        }

        val collisions = owners.filterValues { it.size > 1 }
        assertTrue(
            collisions.isEmpty(),
            "Catalog id collision across shards under '$idPrefix': $collisions",
        )
    }

    private fun idsIn(resource: Resource, idPrefix: String): Set<String> {
        val props = YamlPropertiesFactoryBean()
            .apply { setResources(resource) }
            .getObject()
            ?: return emptySet()
        return props.stringPropertyNames()
            .asSequence()
            .filter { it.startsWith(idPrefix) }
            .map { it.removePrefix(idPrefix).substringBefore('.') }
            .toSet()
    }
}
