package io.bluetape4k.workshop.commerce.reservation.notification

import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class NotificationOutboxTest {
    private val now = Instant.parse("2026-07-19T00:00:00Z")

    @Test
    fun `stable delivery id deduplicates repeated enqueue`() {
        val outbox = InMemoryNotificationOutbox()
        val request = notification("hold-10-confirmed")

        val first = outbox.enqueue(request, now)
        val duplicate = outbox.enqueue(request, now.plusSeconds(1))

        duplicate shouldBeEqualTo first
        outbox.size() shouldBeEqualTo 1
    }

    @Test
    fun `claim lease rejects another worker and stale finalize`() {
        val outbox = InMemoryNotificationOutbox()
        outbox.enqueue(notification("offer-20-created"), now)

        val firstClaim = outbox.claim("offer-20-created", "worker-a", now, Duration.ofSeconds(30))
        val blockedClaim = outbox.claim("offer-20-created", "worker-b", now.plusSeconds(10), Duration.ofSeconds(30))
        val reclaimed = outbox.claim("offer-20-created", "worker-b", now.plusSeconds(31), Duration.ofSeconds(30))

        firstClaim?.attemptCount shouldBeEqualTo 1
        blockedClaim shouldBeEqualTo null
        reclaimed?.attemptCount shouldBeEqualTo 2
        outbox.markDelivered("offer-20-created", "worker-a", now.plusSeconds(32)) shouldBeEqualTo
            FinalizeDisposition.STALE_CLAIM
        outbox.markDelivered("offer-20-created", "worker-b", now.plusSeconds(32)) shouldBeEqualTo
            FinalizeDisposition.APPLIED
    }

    @Test
    fun `retry uses bounded exponential delay and exhausts after five attempts`() {
        val outbox = InMemoryNotificationOutbox()
        val policy = NotificationRetryPolicy(
            maxAttempts = 5,
            initialDelay = Duration.ofSeconds(2),
            maxDelay = Duration.ofSeconds(10),
        )
        outbox.enqueue(notification("hold-10-expired"), now)

        var attemptAt = now
        val expectedNextAttemptAt = listOf(3L, 8L, 17L, 28L)
        expectedNextAttemptAt.forEachIndexed { index, secondsFromStart ->
            val owner = "worker-${index + 1}"
            outbox.claim("hold-10-expired", owner, attemptAt, Duration.ofSeconds(30))?.attemptCount shouldBeEqualTo
                index + 1
            outbox.markFailed(
                "hold-10-expired",
                owner,
                attemptAt.plusSeconds(1),
                NotificationFailureCode.FAKE_TRANSIENT,
                policy,
            ) shouldBeEqualTo
                FinalizeDisposition.APPLIED
            val delivery = outbox.find("hold-10-expired") ?: error("retry delivery is required")
            val retryAt = delivery.nextAttemptAt ?: error("retry time is required")
            delivery.status shouldBeEqualTo NotificationDeliveryStatus.RETRYING
            retryAt shouldBeEqualTo now.plusSeconds(secondsFromStart)
            outbox.claim(
                "hold-10-expired",
                "early-worker",
                retryAt.minusNanos(1),
                Duration.ofSeconds(30),
            ) shouldBeEqualTo null
            attemptAt = retryAt
        }

        outbox.claim("hold-10-expired", "worker-5", attemptAt, Duration.ofSeconds(30))?.attemptCount shouldBeEqualTo 5
        outbox.markFailed(
            "hold-10-expired",
            "worker-5",
            attemptAt.plusSeconds(1),
            NotificationFailureCode.FAKE_TRANSIENT,
            policy,
        ) shouldBeEqualTo
            FinalizeDisposition.APPLIED

        val exhausted = outbox.find("hold-10-expired")
        exhausted?.status shouldBeEqualTo NotificationDeliveryStatus.EXHAUSTED
        exhausted?.attemptCount shouldBeEqualTo 5
        exhausted?.nextAttemptAt shouldBeEqualTo null
    }

    @Test
    fun `provider accepted crash is recovered without a duplicate effect`() {
        val outbox = InMemoryNotificationOutbox()
        val provider = FailFirstDeduplicatingNotificationProvider(failFirstAttempts = 1)
        val policy = NotificationRetryPolicy()
        outbox.enqueue(notification("waitlist-30-offered"), now)

        val first = outbox.claim("waitlist-30-offered", "worker-a", now, Duration.ofSeconds(30))!!
        provider.send(first) shouldBeEqualTo
            ProviderSendResult.RetryableFailure(NotificationFailureCode.FAKE_TRANSIENT)
        outbox.markFailed(
            "waitlist-30-offered",
            "worker-a",
            now.plusSeconds(1),
            NotificationFailureCode.FAKE_TRANSIENT,
            policy,
        )

        val secondAt = outbox.find("waitlist-30-offered")!!.nextAttemptAt!!
        val acceptedBeforeCrash = outbox.claim(
            "waitlist-30-offered",
            "worker-a",
            secondAt,
            Duration.ofSeconds(30),
        )!!
        provider.send(acceptedBeforeCrash) shouldBeEqualTo ProviderSendResult.Accepted

        val recovered = outbox.claim(
            "waitlist-30-offered",
            "worker-b",
            secondAt.plusSeconds(31),
            Duration.ofSeconds(30),
        )!!
        provider.send(recovered) shouldBeEqualTo ProviderSendResult.Duplicate
        outbox.markDelivered("waitlist-30-offered", "worker-b", secondAt.plusSeconds(32)) shouldBeEqualTo
            FinalizeDisposition.APPLIED

        provider.effectCount("waitlist-30-offered") shouldBeEqualTo 1
        outbox.find("waitlist-30-offered")?.status shouldBeEqualTo NotificationDeliveryStatus.DELIVERED
    }

    @Test
    fun `operator redrive is idempotent and resets exhausted delivery`() {
        val outbox = InMemoryNotificationOutbox()
        val policy = NotificationRetryPolicy(maxAttempts = 1)
        outbox.enqueue(notification("hold-40-released"), now)
        outbox.claim("hold-40-released", "worker-a", now, Duration.ofSeconds(30))
        outbox.markFailed(
            "hold-40-released",
            "worker-a",
            now.plusSeconds(1),
            NotificationFailureCode.FAKE_TRANSIENT,
            policy,
        )

        outbox.redrive("hold-40-released", "redrive-command-1", now.plusSeconds(2)) shouldBeEqualTo
            RedriveDisposition.APPLIED
        val redriven = outbox.find("hold-40-released")
        outbox.redrive("hold-40-released", "redrive-command-1", now.plusSeconds(3)) shouldBeEqualTo
            RedriveDisposition.ALREADY_APPLIED

        outbox.find("hold-40-released") shouldBeEqualTo redriven
        redriven?.status shouldBeEqualTo NotificationDeliveryStatus.PENDING
        redriven?.attemptCount shouldBeEqualTo 0
        redriven?.nextAttemptAt shouldBeEqualTo now.plusSeconds(2)
    }

    private fun notification(deliveryId: String) = NotificationRequest(
        deliveryId = deliveryId,
        channel = NotificationChannel.IN_APP,
        templateCode = "reservation-state-changed",
        aggregateId = "reservation-10",
    )
}
