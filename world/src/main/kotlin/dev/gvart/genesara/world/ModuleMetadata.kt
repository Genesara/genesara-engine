package dev.gvart.genesara.world

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

@PackageInfo
@ApplicationModule(
    displayName = "World",
    allowedDependencies = ["engine", "player"],
    type = ApplicationModule.Type.OPEN,
)
object ModuleMetadata
