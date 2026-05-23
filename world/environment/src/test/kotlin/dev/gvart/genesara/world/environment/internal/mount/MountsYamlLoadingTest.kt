package dev.gvart.genesara.world.environment.internal.mount

import dev.gvart.genesara.world.MaintenanceType
import dev.gvart.genesara.world.MountCatalog
import dev.gvart.genesara.world.MountSlot
import dev.gvart.genesara.world.MountType
import dev.gvart.genesara.world.NpcType
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.ConfigurationPropertiesBindingPostProcessor
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MountsYamlLoadingTest {

    @Test
    fun `production mounts_yaml binds the 3 v1 mount types with tamed-from links`() {
        AnnotationConfigApplicationContext().use { ctx ->
            ConfigurationPropertiesBindingPostProcessor.register(ctx)
            ctx.register(MountCatalogConfiguration::class.java)
            ctx.refresh()

            val catalog = ctx.getBean(MountCatalog::class.java)
            assertEquals(3, catalog.all().size)

            val horse = assertNotNull(catalog.byType(MountType("RIDING_HORSE")))
            assertEquals(NpcType("WILD_HORSE"), horse.tamedFrom)
            assertEquals(45, horse.tameability)
            assertEquals(0.6, horse.speedFactor)
            assertEquals(setOf(MountSlot.SADDLE, MountSlot.BARDING, MountSlot.HARNESS), horse.gearSlots)
            assertEquals(MaintenanceType.ANIMAL, horse.maintenanceType)

            val elk = assertNotNull(catalog.byType(MountType("ELK_MOUNT")))
            assertEquals(NpcType("ELK"), elk.tamedFrom)
            assertEquals(30, elk.tameability)
            assertTrue(elk.hpMax in 1..1000)
            assertEquals(MaintenanceType.ANIMAL, elk.maintenanceType)

            val bison = assertNotNull(catalog.byType(MountType("PACK_BISON")))
            assertEquals(NpcType("BISON"), bison.tamedFrom)
            assertEquals(120000, bison.carryCapacityGrams, "bison is the heavy-cargo option")
            assertEquals(4, bison.defense)
            assertEquals(MaintenanceType.ANIMAL, bison.maintenanceType)
        }
    }

    @Test
    fun `byTamedFromNpc indexes the wild-source NPC type back to the mount`() {
        AnnotationConfigApplicationContext().use { ctx ->
            ConfigurationPropertiesBindingPostProcessor.register(ctx)
            ctx.register(MountCatalogConfiguration::class.java)
            ctx.refresh()

            val catalog = ctx.getBean(MountCatalog::class.java)

            val fromHorse = assertNotNull(catalog.byTamedFromNpc(NpcType("WILD_HORSE")))
            assertEquals(MountType("RIDING_HORSE"), fromHorse.type)

            assertNull(catalog.byTamedFromNpc(NpcType("GIANT_RAT")), "non-tameable NPC type yields null")
        }
    }
}
