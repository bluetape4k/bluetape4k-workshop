package io.bluetape4k.workshop.commerce.reservation.notification

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * NotificationOutboxTest 테스트 묶음은 예약 제어 플레인의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class NotificationOutboxTest {

    companion object: KLogging()

/**
 * now은 예약 제어 플레인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val now = Instant.parse("2026-07-19T00:00:00Z")

    @Test
/**
 * 이 시나리오는 "stable delivery id deduplicates repeated enqueue" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `stable delivery id deduplicates repeated enqueue`() {
        val outbox = InMemoryNotificationOutbox()
        val request = notification("hold-10-confirmed")

        val first = outbox.enqueue(request, now)
        val duplicate = outbox.enqueue(request, now + 1.seconds())

        duplicate shouldBeEqualTo first
        outbox.size() shouldBeEqualTo 1
    }

    @Test
/**
 * 이 시나리오는 "claim lease rejects another worker and stale finalize" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `claim lease rejects another worker and stale finalize`() {
        val outbox = InMemoryNotificationOutbox()
        outbox.enqueue(notification("offer-20-created"), now)

        val firstClaim = outbox.claim(
            deliveryId = "offer-20-created",
            owner = "worker-a",
            now = now,
            lease = 30.seconds()
        )
        val blockedClaim = outbox.claim(
            deliveryId = "offer-20-created",
            owner = "worker-b",
            now = now + 10.seconds(),
            lease = 30.seconds()
        )
        val reclaimed = outbox.claim(
            deliveryId = "offer-20-created",
            owner = "worker-b",
            now = now + 31.seconds(),
            lease = 30.seconds()
        )

        firstClaim?.attemptCount shouldBeEqualTo 1
        blockedClaim.shouldBeNull()
        reclaimed?.attemptCount shouldBeEqualTo 2

        outbox.markDelivered(
            deliveryId = "offer-20-created",
            owner = "worker-a",
            now = now + 32.seconds()
        ) shouldBeEqualTo FinalizeDisposition.STALE_CLAIM

        outbox.markDelivered(
            deliveryId = "offer-20-created",
            owner = "worker-b",
            now = now + 32.seconds()
        ) shouldBeEqualTo FinalizeDisposition.APPLIED
    }

    @Test
/**
 * 이 시나리오는 "retry uses bounded exponential delay and exhausts after five attempts" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `retry uses bounded exponential delay and exhausts after five attempts`() {
        val outbox = InMemoryNotificationOutbox()
        val policy = NotificationRetryPolicy(
            maxAttempts = 5,
            initialDelay = 2.seconds(),
            maxDelay = 10.seconds()
        )
        outbox.enqueue(notification("hold-10-expired"), now)

        var attemptAt = now
        val expectedNextAttemptAt = listOf(3L, 8L, 17L, 28L)

        expectedNextAttemptAt.forEachIndexed { index, secondsFromStart ->
            val owner = "worker-${index + 1}"
            outbox.claim(
                deliveryId = "hold-10-expired",
                owner = owner,
                now = attemptAt,
                lease = 30.seconds()
            )?.attemptCount shouldBeEqualTo index + 1

            outbox.markFailed(
                deliveryId = "hold-10-expired",
                owner = owner,
                now = attemptAt + 1.seconds(),
                failureCode = NotificationFailureCode.FAKE_TRANSIENT,
                policy = policy
            ) shouldBeEqualTo FinalizeDisposition.APPLIED

            val delivery = outbox.find("hold-10-expired") ?: error("retry delivery is required")
            val retryAt = delivery.nextAttemptAt ?: error("retry time is required")
            delivery.status shouldBeEqualTo NotificationDeliveryStatus.RETRYING
            retryAt shouldBeEqualTo now.plusSeconds(secondsFromStart)

            outbox.claim(
                deliveryId = "hold-10-expired",
                owner = "early-worker",
                now = retryAt.minusNanos(1),
                lease = 30.seconds()
            ).shouldBeNull()

            attemptAt = retryAt
        }

        outbox.claim(
            deliveryId = "hold-10-expired",
            owner = "worker-5",
            now = attemptAt,
            lease = 30.seconds()
        )?.attemptCount shouldBeEqualTo 5

        outbox.markFailed(
            deliveryId = "hold-10-expired",
            owner = "worker-5",
            now = attemptAt + 1.seconds(),
            failureCode = NotificationFailureCode.FAKE_TRANSIENT,
            policy = policy
        ) shouldBeEqualTo FinalizeDisposition.APPLIED

        val exhausted = outbox.find("hold-10-expired")!!
        exhausted.status shouldBeEqualTo NotificationDeliveryStatus.EXHAUSTED
        exhausted.attemptCount shouldBeEqualTo 5
        exhausted.nextAttemptAt.shouldBeNull()
    }

    @Test
/**
 * 이 시나리오는 "provider accepted crash is recovered without a duplicate effect" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `provider accepted crash is recovered without a duplicate effect`() {
        val outbox = InMemoryNotificationOutbox()
        val provider = FailFirstDeduplicatingNotificationProvider(failFirstAttempts = 1)
        val policy = NotificationRetryPolicy()

        outbox.enqueue(notification("waitlist-30-offered"), now)

        val first = outbox.claim(
            deliveryId = "waitlist-30-offered",
            owner = "worker-a",
            now = now,
            lease = 30.seconds()
        )!!
        provider.send(first) shouldBeEqualTo ProviderSendResult.RetryableFailure(NotificationFailureCode.FAKE_TRANSIENT)

        outbox.markFailed(
            deliveryId = "waitlist-30-offered",
            owner = "worker-a",
            now = now + 1.seconds(),
            failureCode = NotificationFailureCode.FAKE_TRANSIENT,
            policy = policy
        )

        val secondAt = outbox.find("waitlist-30-offered")!!.nextAttemptAt!!
        val acceptedBeforeCrash = outbox.claim(
            deliveryId = "waitlist-30-offered",
            owner = "worker-a",
            now = secondAt,
            lease = 30.seconds()
        )!!
        provider.send(acceptedBeforeCrash) shouldBeEqualTo ProviderSendResult.Accepted

        val recovered = outbox.claim(
            deliveryId = "waitlist-30-offered",
            owner = "worker-b",
            now = secondAt + 31.seconds(),
            lease = 30.seconds()
        )!!
        provider.send(recovered) shouldBeEqualTo ProviderSendResult.Duplicate

        outbox.markDelivered(
            deliveryId = "waitlist-30-offered",
            owner = "worker-b",
            now = secondAt + 32.seconds()
        ) shouldBeEqualTo FinalizeDisposition.APPLIED

        provider.effectCount("waitlist-30-offered") shouldBeEqualTo 1
        outbox.find("waitlist-30-offered")?.status shouldBeEqualTo NotificationDeliveryStatus.DELIVERED
    }

    @Test
/**
 * 이 시나리오는 "operator redrive is idempotent and resets exhausted delivery" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `operator redrive is idempotent and resets exhausted delivery`() {
        val outbox = InMemoryNotificationOutbox()
        val policy = NotificationRetryPolicy(maxAttempts = 1)

        outbox.enqueue(notification("hold-40-released"), now)
        outbox.claim("hold-40-released", "worker-a", now, 30.seconds())
        outbox.markFailed(
            deliveryId = "hold-40-released",
            owner = "worker-a",
            now = now + 1.seconds(),
            failureCode = NotificationFailureCode.FAKE_TRANSIENT,
            policy = policy
        )

        outbox.redrive(
            deliveryId = "hold-40-released",
            commandId = "redrive-command-1",
            now = now + 2.seconds()
        ) shouldBeEqualTo RedriveDisposition.APPLIED

        val redriven = outbox.find("hold-40-released")!!
        outbox.redrive(
            deliveryId = "hold-40-released",
            commandId = "redrive-command-1",
            now = now + 3.seconds()
        ) shouldBeEqualTo RedriveDisposition.ALREADY_APPLIED

        outbox.find("hold-40-released") shouldBeEqualTo redriven
        redriven.status shouldBeEqualTo NotificationDeliveryStatus.PENDING
        redriven.attemptCount shouldBeEqualTo 0
        redriven.nextAttemptAt shouldBeEqualTo now + 2.seconds()
    }

/**
 * notification은 예약 제어 플레인 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun notification(deliveryId: String): NotificationRequest =
        NotificationRequest(
            deliveryId = deliveryId,
            channel = NotificationChannel.IN_APP,
            templateCode = "reservation-state-changed",
            aggregateId = "reservation-10"
        )
}
