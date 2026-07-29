package io.bluetape4k.workshop.spring.modulith.boundaries.invalid.payment

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

@ApplicationModule(allowedDependencies = ["ordering :: events"])
@PackageInfo
/**
 * invalid payment module fixture 를 위한 test-only metadata 입니다.
 */
class ModuleMetadata
