package dev.gvart.genesara.world

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

@PackageInfo
@ApplicationModule(
    displayName = "World",
    allowedDependencies = ["engine", "player", "world.body", "world.combat", "world.economy", "world.environment", "world.clan"],
    type = ApplicationModule.Type.OPEN,
)
object ModuleMetadata
