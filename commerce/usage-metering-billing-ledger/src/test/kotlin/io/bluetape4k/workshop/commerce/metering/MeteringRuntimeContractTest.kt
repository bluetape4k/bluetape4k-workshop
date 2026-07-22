package io.bluetape4k.workshop.commerce.metering

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotContain
import org.junit.jupiter.api.Test
import java.lang.management.ManagementFactory

class MeteringRuntimeContractTest {

    @Test
    fun `runtime uses Java 25 without preview`() {
        Runtime.version().feature() shouldBeEqualTo 25
        ManagementFactory.getRuntimeMXBean().inputArguments shouldNotContain "--enable-preview"
    }
}
