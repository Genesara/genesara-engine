package dev.gvart.genesara.world.clan

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

@PackageInfo
@ApplicationModule(
    displayName = "World Clan Zone",
    allowedDependencies = ["world", "engine", "player"],
    type = ApplicationModule.Type.OPEN,
)
object ModuleMetadata
