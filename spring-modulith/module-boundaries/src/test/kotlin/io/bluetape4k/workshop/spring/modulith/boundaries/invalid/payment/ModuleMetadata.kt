package io.bluetape4k.workshop.spring.modulith.boundaries.invalid.payment

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/**
 * invalid payment module fixture 를 위한 test-only metadata 입니다.
 */
@ApplicationModule(allowedDependencies = ["ordering :: events"])
@PackageInfo
class ModuleMetadata
