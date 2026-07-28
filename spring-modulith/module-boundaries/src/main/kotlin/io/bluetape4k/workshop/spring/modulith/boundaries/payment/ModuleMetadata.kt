package io.bluetape4k.workshop.spring.modulith.boundaries.payment

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

@ApplicationModule(allowedDependencies = ["ordering :: events"])
@PackageInfo
/**
 * payment application module 을 위한 Spring Modulith metadata 입니다.
 */
class ModuleMetadata
