package dev.gvart.agenticrpg.api

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

@PackageInfo
@ApplicationModule(
    displayName = "API / MCP",
    allowedDependencies = ["engine", "world", "player", "account", "admin"],
    type = ApplicationModule.Type.OPEN,
)
object ModuleMetadata
