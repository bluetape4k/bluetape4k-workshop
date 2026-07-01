package io.bluetape4k.workshop.spring.modulith.boundaries.invalid.payment

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

@ApplicationModule(allowedDependencies = ["ordering :: events"])
@PackageInfo
/**
 * Test-only metadata for the invalid payment module fixture.
 */
class ModuleMetadata
