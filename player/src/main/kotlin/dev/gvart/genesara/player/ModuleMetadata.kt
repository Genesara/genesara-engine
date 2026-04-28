package dev.gvart.genesara.player

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

@PackageInfo
@ApplicationModule(
    displayName = "Player",
    allowedDependencies = ["engine", "account"],
    type = ApplicationModule.Type.OPEN,
)
object ModuleMetadata
