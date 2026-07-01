package io.bluetape4k.workshop.spring.modulith.boundaries.notification

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

@ApplicationModule(allowedDependencies = ["ordering :: events"])
@PackageInfo
/**
 * Spring Modulith metadata for the notification application module.
 */
class ModuleMetadata
