package io.bluetape4k.workshop.commerce.reservation.notification

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requireNotBlank
import java.time.Duration
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class InMemoryNotificationOutbox {
    private val lock = ReentrantLock()
    private val deliveries = linkedMapOf<String, NotificationDelivery>()
    private val appliedRedriveCommands = mutableSetOf<Pair<String, String>>()

    fun enqueue(request: NotificationRequest, now: Instant): NotificationDelivery = lock.withLock {
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
            failureCode = null,
        ).also { delivery ->
            deliveries[delivery.deliveryId] = delivery
            log.debug { "notification_enqueued deliveryId=${delivery.deliveryId} channel=${delivery.channel}" }
        }
    }

    fun find(deliveryId: String): NotificationDelivery? = lock.withLock {
        deliveries[deliveryId]
    }

    fun size(): Int = lock.withLock { deliveries.size }

    fun claim(
        deliveryId: String,
        owner: String,
        now: Instant,
        lease: Duration,
    ): NotificationDelivery? = lock.withLock {
        val validOwner = owner.requireNotBlank("owner")
        require(!lease.isNegative && !lease.isZero) { "lease must be positive" }
        val current = deliveries[deliveryId] ?: return null
        if (!current.canBeClaimedAt(now)) {
            return null
        }

        current.copy(
            status = NotificationDeliveryStatus.IN_FLIGHT,
            attemptCount = current.attemptCount + 1,
            claimOwner = validOwner,
            claimUntil = now.plus(lease),
            failureCode = null,
        ).also { claimed ->
            deliveries[deliveryId] = claimed
            log.debug {
                "notification_claimed deliveryId=$deliveryId attempt=${claimed.attemptCount}"
            }
        }
    }

    fun markDelivered(
        deliveryId: String,
        owner: String,
        now: Instant,
    ): FinalizeDisposition = finalize(deliveryId, owner, now) { current ->
        current.copy(
            status = NotificationDeliveryStatus.DELIVERED,
            nextAttemptAt = null,
            claimOwner = null,
            claimUntil = null,
            failureCode = null,
        ).also {
            log.debug { "notification_delivered deliveryId=$deliveryId attempt=${current.attemptCount}" }
        }
    }

    fun markFailed(
        deliveryId: String,
        owner: String,
        now: Instant,
        failureCode: NotificationFailureCode,
        policy: NotificationRetryPolicy,
    ): FinalizeDisposition {
        return finalize(deliveryId, owner, now) { current ->
            if (current.attemptCount >= policy.maxAttempts) {
                current.copy(
                    status = NotificationDeliveryStatus.EXHAUSTED,
                    nextAttemptAt = null,
                    claimOwner = null,
                    claimUntil = null,
                    failureCode = failureCode,
                ).also {
                    log.warn {
                        "notification_exhausted deliveryId=$deliveryId attempt=${current.attemptCount} " +
                            "failureCode=$failureCode"
                    }
                }
            } else {
                current.copy(
                    status = NotificationDeliveryStatus.RETRYING,
                    nextAttemptAt = now.plus(policy.delayAfter(current.attemptCount)),
                    claimOwner = null,
                    claimUntil = null,
                    failureCode = failureCode,
                ).also { retrying ->
                    log.warn {
                        "notification_retry_scheduled deliveryId=$deliveryId attempt=${current.attemptCount} " +
                            "nextAttemptAt=${retrying.nextAttemptAt} failureCode=$failureCode"
                    }
                }
            }
        }
    }

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
            failureCode = null,
        )
        appliedRedriveCommands += commandKey
        log.warn { "notification_redriven deliveryId=$deliveryId" }
        RedriveDisposition.APPLIED
    }

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

    private fun NotificationDelivery.canBeClaimedAt(now: Instant): Boolean = when (status) {
        NotificationDeliveryStatus.PENDING,
        NotificationDeliveryStatus.RETRYING,
        -> nextAttemptAt?.let { !now.isBefore(it) } == true

        NotificationDeliveryStatus.IN_FLIGHT -> claimUntil?.let { !now.isBefore(it) } == true
        NotificationDeliveryStatus.DELIVERED,
        NotificationDeliveryStatus.EXHAUSTED,
        -> false
    }

    private companion object : KLogging()
}
