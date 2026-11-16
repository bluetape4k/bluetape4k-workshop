package io.bluetape4k.workshop.commerce.metering

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test
import java.lang.management.ManagementFactory

class MeteringRuntimeContractTest {

    companion object: KLogging()

    @Test
    fun `runtime uses Java 25 without preview`() {
        Runtime.version().feature() shouldBeEqualTo 25
        ManagementFactory.getRuntimeMXBean().inputArguments shouldContain "--enable-preview"
    }
}
