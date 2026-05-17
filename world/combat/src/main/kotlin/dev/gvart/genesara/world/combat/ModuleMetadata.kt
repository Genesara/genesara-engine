package dev.gvart.genesara.world.combat

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

@PackageInfo
@ApplicationModule(
    displayName = "World Combat Zone",
    allowedDependencies = ["world", "engine", "player"],
    type = ApplicationModule.Type.OPEN,
)
object ModuleMetadata
