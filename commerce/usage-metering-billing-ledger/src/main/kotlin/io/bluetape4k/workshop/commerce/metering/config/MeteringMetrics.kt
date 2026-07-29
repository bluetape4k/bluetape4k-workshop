package io.bluetape4k.workshop.commerce.metering.config

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

@Component
class MeteringMetrics(private val registry: MeterRegistry) {
    fun command(operation: String, result: String): Unit =
        Counter.builder("workshop.metering.commands")
            .tag("operation", operation)
            .tag("result", result)
            .register(registry)
            .increment()

    fun close(result: String): Unit =
        Counter.builder("workshop.metering.close.batches")
            .tag("result", result)
            .register(registry)
            .increment()
}
