package dev.gvart.genesara.world.economy

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

@PackageInfo
@ApplicationModule(
    displayName = "World Economy Zone",
    allowedDependencies = ["world", "engine", "player"],
    type = ApplicationModule.Type.OPEN,
)
object ModuleMetadata
