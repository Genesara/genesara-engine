package dev.gvart.agenticrpg.account

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

@PackageInfo
@ApplicationModule(
    displayName = "Account",
    allowedDependencies = [],
    type = ApplicationModule.Type.OPEN,
)
object ModuleMetadata
