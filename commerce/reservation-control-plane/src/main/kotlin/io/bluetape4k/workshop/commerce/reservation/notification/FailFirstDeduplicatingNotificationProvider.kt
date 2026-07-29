package io.bluetape4k.workshop.commerce.reservation.notification

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requireZeroOrPositiveNumber
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 설정한 횟수만큼 실패하고 각 delivery를 한 번만 적용하는 결정적 fake provider입니다.
 * 외부 delivery service를 도입하지 않고 provider retry와 deduplication semantic을 모델링합니다.
 */
internal class FailFirstDeduplicatingNotificationProvider(
    private val failFirstAttempts: Int = 1,
) {
    private val lock = ReentrantLock()
    private val attempts = mutableMapOf<String, Int>()
    private val accepted = mutableSetOf<String>()

    init {
        failFirstAttempts.requireZeroOrPositiveNumber("failFirstAttempts")
    }

    fun send(delivery: NotificationDelivery): ProviderSendResult =
        lock.withLock {
            if (delivery.deliveryId in accepted) {
                log.debug { "fake_notification_deduplicated deliveryId=${delivery.deliveryId}" }
                return ProviderSendResult.Duplicate
            }

            val attempt = attempts.getOrDefault(delivery.deliveryId, 0) + 1
            attempts[delivery.deliveryId] = attempt
            if (attempt <= failFirstAttempts) {
                log.warn {
                    "fake_notification_failed deliveryId=${delivery.deliveryId} attempt=$attempt code=$TRANSIENT_CODE"
                }
                return ProviderSendResult.RetryableFailure(NotificationFailureCode.FAKE_TRANSIENT)
            }

            accepted += delivery.deliveryId
            log.debug { "fake_notification_accepted deliveryId=${delivery.deliveryId} attempt=$attempt" }
            ProviderSendResult.Accepted
        }

    fun effectCount(deliveryId: String): Int =
        lock.withLock {
            if (deliveryId in accepted) 1 else 0
        }

    private companion object : KLogging() {
        val TRANSIENT_CODE = NotificationFailureCode.FAKE_TRANSIENT
    }
}
