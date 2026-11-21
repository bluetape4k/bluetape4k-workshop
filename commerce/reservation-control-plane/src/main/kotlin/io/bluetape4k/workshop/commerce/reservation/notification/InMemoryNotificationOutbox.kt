package io.bluetape4k.workshop.commerce.reservation.notification

import io.bluetape4k.javatimes.requirePositiveDuration
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requireNotBlank
import java.time.Duration
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * token-like claim, bounded retry, exhaustion, idempotent redrive를 제공하는 결정적 outbox fixture입니다.
 * production command path는 [NotificationDeliveryRepository]를 사용하며, 이 fixture는 delivery contract를
 * 검증합니다.
 */
internal class InMemoryNotificationOutbox {

    private companion object: KLogging()
    /**
     * 좌석 예약·대기열 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */

    private val lock = ReentrantLock()
    /**
     * 좌석 예약·대기열 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val deliveries = linkedMapOf<String, NotificationDelivery>()
    /**
     * 좌석 예약·대기열 경계의 전달 데이터입니다. 스키마·크기·무결성을 검증한 뒤 저장하거나 발행합니다.
     */
    private val appliedRedriveCommands = mutableSetOf<Pair<String, String>>()

    /**
     * enqueue은 예약 제어면의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param request 예약 제어면 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun enqueue(
        request: NotificationRequest,
        now: Instant,
    ): NotificationDelivery = lock.withLock {
        deliveries[request.deliveryId]?.also {
            log.debug { "notification_enqueue_deduplicated deliveryId=${request.deliveryId}" }
            return it
        }

        NotificationDelivery(
            deliveryId = request.deliveryId,
            channel = request.channel,
            templateCode = request.templateCode,
            aggregateId = request.aggregateId,
            status = NotificationDeliveryStatus.PENDING,
            attemptCount = 0,
            nextAttemptAt = now,
            claimOwner = null,
            claimUntil = null,
            failureCode = null
        ).also { delivery ->
            deliveries[delivery.deliveryId] = delivery
            log.debug { "notification_enqueued deliveryId=${delivery.deliveryId} channel=${delivery.channel}" }
        }
    }

    /**
     * find은 예약 제어면의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param deliveryId 처리할 예약 제어면 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    fun find(deliveryId: String): NotificationDelivery? = lock.withLock {
        deliveries[deliveryId]
    }

    /**
     * size은 예약 제어면의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    fun size(): Int = lock.withLock { deliveries.size }

    /** concurrent worker가 동시에 finalize하지 못하도록 pending, due, expired-lease work만 claim합니다. */
    /** concurrent worker가 동시에 finalize하지 못하도록 pending, due, expired-lease work만 claim합니다. */
    fun claim(
        deliveryId: String,
        owner: String,
        now: Instant,
        lease: Duration,
    ): NotificationDelivery? = lock.withLock {
        val validOwner = owner.requireNotBlank("owner")
        lease.requirePositiveDuration("lease")

        val current = deliveries[deliveryId] ?: return null
        if (!current.canBeClaimedAt(now)) {
            return null
        }

        current
            .copy(
                status = NotificationDeliveryStatus.IN_FLIGHT,
                attemptCount = current.attemptCount + 1,
                claimOwner = validOwner,
                claimUntil = now.plus(lease),
                failureCode = null
            )
            .also { claimed ->
                deliveries[deliveryId] = claimed
                log.debug {
                    "notification_claimed deliveryId=$deliveryId attempt=${claimed.attemptCount}"
                }
            }
    }

    /**
     * markDelivered은 예약 제어면 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param deliveryId 처리할 예약 제어면 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param owner 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun markDelivered(
        deliveryId: String,
        owner: String,
        now: Instant,
    ): FinalizeDisposition = finalize(deliveryId, owner, now) { current ->
        current
            .copy(
                status = NotificationDeliveryStatus.DELIVERED,
                nextAttemptAt = null,
                claimOwner = null,
                claimUntil = null,
                failureCode = null
            )
            .also {
                log.debug { "notification_delivered deliveryId=$deliveryId attempt=${current.attemptCount}" }
            }
    }

    /**
     * markFailed은 예약 제어면 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param deliveryId 처리할 예약 제어면 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param owner 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param failureCode 업무 처리에 필요한 failureCode 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param policy 업무 처리에 필요한 policy 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun markFailed(
        deliveryId: String,
        owner: String,
        now: Instant,
        failureCode: NotificationFailureCode,
        policy: NotificationRetryPolicy,
    ): FinalizeDisposition = finalize(deliveryId, owner, now) { current ->
        if (current.attemptCount >= policy.maxAttempts) {
            current
                .copy(
                    status = NotificationDeliveryStatus.EXHAUSTED,
                    nextAttemptAt = null,
                    claimOwner = null,
                    claimUntil = null,
                    failureCode = failureCode
                )
                .also {
                    log.warn {
                        "notification_exhausted deliveryId=$deliveryId attempt=${current.attemptCount} " +
                                "failureCode=$failureCode"
                    }
                }
        } else {
            current
                .copy(
                    status = NotificationDeliveryStatus.RETRYING,
                    nextAttemptAt = now.plus(policy.delayAfter(current.attemptCount)),
                    claimOwner = null,
                    claimUntil = null,
                    failureCode = failureCode
                )
                .also { retrying ->
                    log.warn {
                        "notification_retry_scheduled deliveryId=$deliveryId attempt=${current.attemptCount} " +
                                "nextAttemptAt=${retrying.nextAttemptAt} failureCode=$failureCode"
                    }
                }
        }
    }

    /**
     * redrive은 예약 제어면의 실패·지연 데이터를 복구할 때 원본 revision과 중복 키를 확인하고, 재시도 가능한 항목만 안전하게 재처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param deliveryId 처리할 예약 제어면 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param commandId 처리할 예약 제어면 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun redrive(
        deliveryId: String,
        commandId: String,
        now: Instant,
    ): RedriveDisposition = lock.withLock {
        val validCommandId = commandId.requireNotBlank("commandId")
        val commandKey = deliveryId to validCommandId
        if (commandKey in appliedRedriveCommands) {
            return RedriveDisposition.ALREADY_APPLIED
        }
        val current = deliveries[deliveryId] ?: return RedriveDisposition.NOT_FOUND
        if (current.status != NotificationDeliveryStatus.EXHAUSTED) {
            return RedriveDisposition.NOT_EXHAUSTED
        }

        deliveries[deliveryId] = current.copy(
            status = NotificationDeliveryStatus.PENDING,
            attemptCount = 0,
            nextAttemptAt = now,
            claimOwner = null,
            claimUntil = null,
            failureCode = null
        )
        appliedRedriveCommands += commandKey
        log.warn { "notification_redriven deliveryId=$deliveryId" }
        RedriveDisposition.APPLIED
    }

    /**
     * finalize은 예약 제어면의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param deliveryId 처리할 예약 제어면 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param owner 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param transform 업무 처리에 필요한 transform 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun finalize(
        deliveryId: String,
        owner: String,
        now: Instant,
        transform: (NotificationDelivery) -> NotificationDelivery,
    ): FinalizeDisposition = lock.withLock {
        val validOwner = owner.requireNotBlank("owner")
        val current = deliveries[deliveryId] ?: return FinalizeDisposition.NOT_FOUND
        if (current.status == NotificationDeliveryStatus.DELIVERED ||
            current.status == NotificationDeliveryStatus.EXHAUSTED
        ) {
            return FinalizeDisposition.TERMINAL
        }

        if (current.status != NotificationDeliveryStatus.IN_FLIGHT ||
            current.claimOwner != validOwner ||
            current.claimUntil?.isAfter(now) != true
        ) {
            log.warn { "notification_finalize_rejected deliveryId=$deliveryId reason=stale_claim" }
            return FinalizeDisposition.STALE_CLAIM
        }

        deliveries[deliveryId] = transform(current)
        FinalizeDisposition.APPLIED
    }

    /**
     * canBeClaimedAt은 예약 제어면의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를 거절합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    private fun NotificationDelivery.canBeClaimedAt(now: Instant): Boolean = when (status) {
        NotificationDeliveryStatus.PENDING,
        NotificationDeliveryStatus.RETRYING,
                                             -> nextAttemptAt?.let { !now.isBefore(it) } == true
        NotificationDeliveryStatus.IN_FLIGHT -> claimUntil?.let { !now.isBefore(it) } == true
        NotificationDeliveryStatus.DELIVERED,
        NotificationDeliveryStatus.EXHAUSTED,
                                             -> false
    }
}
