package io.bluetape4k.workshop.imageprocessing.profile.service

import io.bluetape4k.assertions.shouldBeEqualTo
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test

class ProfileImageMetricsTest {

    @Test
    fun metrics_use_low_cardinality_tags() {
        val registry = SimpleMeterRegistry()
        val metrics = ProfileImageMetrics(registry)

        metrics.uploadAccepted("image/jpeg")
        metrics.transition("approved", "approved")
        metrics.cleanup("deleted")
        metrics.moderation(1_000_000, "approved")

        registry.meters.flatMap { it.id.tags }.map { it.key }.toSet() shouldBeEqualTo setOf("contentType", "status", "result")
        registry.counter(ProfileImageMetrics.UPLOAD_ACCEPTED, "contentType", "image/jpeg").count() shouldBeEqualTo 1.0
    }
}
