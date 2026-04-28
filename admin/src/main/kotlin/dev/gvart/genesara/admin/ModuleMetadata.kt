package dev.gvart.genesara.admin

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

@PackageInfo
@ApplicationModule(
    displayName = "Admin",
    allowedDependencies = [],
    type = ApplicationModule.Type.OPEN,
)
object ModuleMetadata
