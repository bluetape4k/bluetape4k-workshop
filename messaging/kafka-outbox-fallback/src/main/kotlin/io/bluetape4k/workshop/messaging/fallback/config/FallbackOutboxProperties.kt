package io.bluetape4k.workshop.messaging.fallback.config

import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Positive
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration

/**
 * Validated configuration for the Kafka-first outbox fallback workshop module.
 */
@Validated
@ConfigurationProperties("workshop.kafka-outbox-fallback")
data class FallbackOutboxProperties(
    val topic: String = "order-events",
    @field:Min(3)
    @field:Max(3)
    val directPublishAttempts: Int = 3,
    val directPublishTimeout: Duration = Duration.ofMillis(500),
    val directPublishTotalTimeout: Duration = Duration.ofMillis(1600),
    @field:Positive
    val relayMaxRetries: Int = 3,
    @field:Positive
    val relayBatchSize: Int = 25,
    val relayFixedDelay: Duration = Duration.ofSeconds(2),
    val relayClaimTtl: Duration = Duration.ofSeconds(30),
    val reconcilerGrace: Duration = Duration.ofSeconds(30),
    @field:Min(1024)
    @field:Max(65_536)
    val maxPayloadBytes: Int = 8192,
    val directPublishEnabled: Boolean = true,
    val relayEnabled: Boolean = true,
    val reconcilerEnabled: Boolean = true,
    val demoAdminEndpointsEnabled: Boolean = false,
) {
    @AssertTrue(message = "topic must be order-events")
    fun isTopicAllowed(): Boolean = topic == "order-events"

    @AssertTrue(message = "directPublishTimeout must be positive")
    fun isDirectPublishTimeoutPositive(): Boolean = !directPublishTimeout.isZero && !directPublishTimeout.isNegative

    @AssertTrue(message = "directPublishTotalTimeout must be positive")
    fun isDirectPublishTotalTimeoutPositive(): Boolean =
        !directPublishTotalTimeout.isZero && !directPublishTotalTimeout.isNegative

    @AssertTrue(message = "relayFixedDelay must be positive")
    fun isRelayFixedDelayPositive(): Boolean = !relayFixedDelay.isZero && !relayFixedDelay.isNegative

    @AssertTrue(message = "relayClaimTtl must be positive")
    fun isRelayClaimTtlPositive(): Boolean = !relayClaimTtl.isZero && !relayClaimTtl.isNegative

    @AssertTrue(message = "reconcilerGrace must be positive")
    fun isReconcilerGracePositive(): Boolean = !reconcilerGrace.isZero && !reconcilerGrace.isNegative
}
