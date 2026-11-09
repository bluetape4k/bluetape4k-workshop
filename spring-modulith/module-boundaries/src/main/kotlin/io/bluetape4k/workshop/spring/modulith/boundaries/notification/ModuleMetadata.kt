package io.bluetape4k.workshop.spring.modulith.boundaries.notification

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/**
 * notification application module 을 위한 Spring Modulith metadata 입니다.
 */
@ApplicationModule(allowedDependencies = ["ordering :: events"])
@PackageInfo
class ModuleMetadata
