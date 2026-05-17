package dev.gvart.genesara.world.environment

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

@PackageInfo
@ApplicationModule(
    displayName = "World Environment Zone",
    allowedDependencies = ["world", "engine", "player"],
    type = ApplicationModule.Type.OPEN,
)
object ModuleMetadata
