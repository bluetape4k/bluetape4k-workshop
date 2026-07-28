package io.bluetape4k.workshop.spring.modulith.boundaries.ordering

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

@ApplicationModule(allowedDependencies = ["catalog :: api"])
@PackageInfo
/**
 * ordering application module 을 위한 Spring Modulith metadata 입니다.
 */
class ModuleMetadata
