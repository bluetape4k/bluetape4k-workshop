package io.bluetape4k.workshop.commerce.usagebilling.usage

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test

class UsageServiceApplicationContractTest {

    companion object: KLogging()

    @Test
    fun `usage service exposes its own boot application class`() {
        Class.forName("io.bluetape4k.workshop.commerce.usagebilling.usage.UsageServiceApplication").name shouldBeEqualTo
                "io.bluetape4k.workshop.commerce.usagebilling.usage.UsageServiceApplication"
    }
}
