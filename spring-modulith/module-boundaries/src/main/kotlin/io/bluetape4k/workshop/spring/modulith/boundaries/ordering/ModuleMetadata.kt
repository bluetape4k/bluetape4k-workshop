package io.bluetape4k.workshop.spring.modulith.boundaries.ordering

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

@ApplicationModule(allowedDependencies = ["catalog :: api"])
@PackageInfo
/**
 * Spring Modulith metadata for the ordering application module.
 */
class ModuleMetadata
