package io.bluetape4k.workshop.spring.modulith.boundaries

import com.tngtech.archunit.core.importer.ImportOption
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.workshop.spring.modulith.boundaries.invalid.InvalidBoundaryApplication
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.modulith.core.ApplicationModules
import org.springframework.modulith.core.Violations

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ApplicationModuleBoundaryTest {

    @Test
    fun `application modules allow only exported catalog API and order events`() {
        ApplicationModules.of(ModuleBoundariesApplication::class.java).verify()
    }

    @Test
    fun `boundary verifier rejects payment dependency on ordering internals`() {
        val violations = assertFailsWith<Violations> {
            ApplicationModules
                .of(InvalidBoundaryApplication::class.java, ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
                .verify()
        }

        val messages = violations.messages.joinToString("\n")
        messages shouldContain "ordering"
        messages shouldContain "internal"
    }
}
