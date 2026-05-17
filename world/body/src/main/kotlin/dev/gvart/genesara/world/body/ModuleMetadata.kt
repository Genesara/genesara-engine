package dev.gvart.genesara.world.body

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

@PackageInfo
@ApplicationModule(
    displayName = "World Body Zone",
    allowedDependencies = ["world", "engine", "player"],
    type = ApplicationModule.Type.OPEN,
)
object ModuleMetadata
