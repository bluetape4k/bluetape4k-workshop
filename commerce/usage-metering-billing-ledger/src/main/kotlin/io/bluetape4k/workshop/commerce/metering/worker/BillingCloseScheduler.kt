package io.bluetape4k.workshop.commerce.metering.worker

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.metering.application.BillingCloseService
import io.bluetape4k.workshop.commerce.metering.config.MeteringMetrics
import io.bluetape4k.workshop.commerce.metering.config.MeteringProperties
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class BillingCloseScheduler(
    private val service: BillingCloseService,
    private val properties: MeteringProperties,
    private val metrics: MeteringMetrics,
) {

    companion object: KLogging()

    @Scheduled(fixedDelayString = "\${workshop.metering.close.scheduler-delay:PT5S}")
    fun process() {
        if (!properties.close.schedulerEnabled) return
        val processed = service.processAvailable()
        metrics.close(if (processed == 0) "idle" else "processed")
    }
}
