package io.bluetape4k.workshop.imageprocessing.profile.service

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

@Component
/**
 * Emits low-cardinality Micrometer meters for the moderation example.
 */
class ProfileImageMetrics(
    private val meterRegistry: MeterRegistry,
) {

    fun uploadAccepted(contentType: String) = meterRegistry.counter(UPLOAD_ACCEPTED, "contentType", contentType).increment()
    fun uploadRejected(reason: String) = meterRegistry.counter(UPLOAD_REJECTED, "reason", reason).increment()
    fun transition(status: String, result: String) = meterRegistry.counter(TRANSITION, "status", status, "result", result).increment()
    fun cleanup(result: String) = meterRegistry.counter(CLEANUP, "result", result).increment()
    fun moderation(durationNanos: Long, result: String) {
        Timer.builder(MODERATION_DURATION)
            .tag("result", result)
            .register(meterRegistry)
            .record(durationNanos, TimeUnit.NANOSECONDS)
    }

    companion object {
        const val UPLOAD_ACCEPTED = "workshop.profile.images.upload.accepted"
        const val UPLOAD_REJECTED = "workshop.profile.images.upload.rejected"
        const val MODERATION_DURATION = "workshop.profile.images.moderation.duration"
        const val TRANSITION = "workshop.profile.images.transition"
        const val CLEANUP = "workshop.profile.images.cleanup"
    }
}
