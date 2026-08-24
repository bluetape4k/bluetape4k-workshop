package io.bluetape4k.workshop.optimization.shiftcoverage.application

import io.bluetape4k.codec.Base58
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.EffectKey
import java.time.Instant
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service

enum class ShiftCoverageOutboxStatus { PENDING, CLAIMED, STARTED, APPLIED, COMPLETED, RETRYABLE, DEAD_LETTER, DELIVERY_UNKNOWN }

data class ShiftCoverageOutboxRecord(
    val effectKey: EffectKey,
    val requestId: String,
    val status: ShiftCoverageOutboxStatus = ShiftCoverageOutboxStatus.PENDING,
    val attempt: Int = 0,
    val nextAttemptAt: Instant = Instant.EPOCH,
    val leaseOwner: String? = null,
    val leaseToken: String? = null,
    val leaseExpiresAt: Instant? = null,
    val definitiveLookupNotFound: Boolean = false,
    val lastError: String? = null,
)

class ShiftCoverageOutboxRedriveRejected(message: String) : IllegalStateException(message)

/** DB outbox transition과 lease fence를 deterministic in-memory로 검증하는 seam입니다. */
@Profile("demo")
@Service
class ShiftCoverageOutboxStore {
    private val records = ConcurrentHashMap<EffectKey, ShiftCoverageOutboxRecord>()

    fun enqueue(requestId: String, effectKey: EffectKey, now: Instant = Instant.now()): ShiftCoverageOutboxRecord =
        records.computeIfAbsent(effectKey) { ShiftCoverageOutboxRecord(effectKey, requestId, nextAttemptAt = now) }

    fun find(effectKey: EffectKey): ShiftCoverageOutboxRecord? = records[effectKey]

    fun claim(owner: String, now: Instant): ShiftCoverageOutboxRecord? {
        val candidate = records.values.asSequence()
            .filter { it.status == ShiftCoverageOutboxStatus.PENDING || it.status == ShiftCoverageOutboxStatus.RETRYABLE }
            .filter { !it.nextAttemptAt.isAfter(now) }
            .sortedBy { it.effectKey.value }
            .firstOrNull() ?: return null
        val claimed = AtomicBoolean(false)
        val result = records.computeIfPresent(candidate.effectKey) { _, current ->
            if ((current.status == ShiftCoverageOutboxStatus.PENDING || current.status == ShiftCoverageOutboxStatus.RETRYABLE) &&
                !current.nextAttemptAt.isAfter(now)
            ) {
                claimed.set(true)
                current.copy(
                    status = ShiftCoverageOutboxStatus.CLAIMED,
                    leaseOwner = owner,
                    leaseToken = Base58.randomString(22),
                    leaseExpiresAt = now.plusSeconds(30),
                )
            } else current
        }
        return if (claimed.get()) result else null
    }

    fun markStarted(record: ShiftCoverageOutboxRecord, owner: String): Boolean = transition(record.effectKey) { current ->
        if (current.status == ShiftCoverageOutboxStatus.CLAIMED && current.leaseOwner == owner && current.leaseToken == record.leaseToken) {
            current.copy(status = ShiftCoverageOutboxStatus.STARTED)
        } else null
    } != null

    fun markDeliveryUnknown(effectKey: EffectKey, owner: String, leaseToken: String): Boolean = transition(effectKey) { current ->
        if (current.status == ShiftCoverageOutboxStatus.STARTED && current.leaseOwner == owner && current.leaseToken == leaseToken) {
            current.copy(status = ShiftCoverageOutboxStatus.DELIVERY_UNKNOWN, lastError = "delivery outcome unknown")
        } else null
    } != null

    fun reconcile(effectKey: EffectKey, providerFound: Boolean, now: Instant): ShiftCoverageOutboxRecord {
        val updated = transition(effectKey) { current ->
            if (current.status != ShiftCoverageOutboxStatus.DELIVERY_UNKNOWN) null
            else current.copy(
                status = if (providerFound) ShiftCoverageOutboxStatus.APPLIED else ShiftCoverageOutboxStatus.RETRYABLE,
                nextAttemptAt = now,
                definitiveLookupNotFound = !providerFound,
            )
        }
        return checkNotNull(updated) { "outbox effect is not awaiting definitive lookup" }
    }

    fun redrive(effectKey: EffectKey, operator: String, reason: String, now: Instant = Instant.now()): ShiftCoverageOutboxRecord? {
        require(operator.isNotBlank() && reason.isNotBlank()) { "operator and reason are required" }
        val updated = transition(effectKey) { current ->
            if (current.status != ShiftCoverageOutboxStatus.RETRYABLE || !current.definitiveLookupNotFound) {
                throw ShiftCoverageOutboxRedriveRejected("only definitively NOT_FOUND retryable effects can be redriven")
            }
            current.copy(status = ShiftCoverageOutboxStatus.PENDING, nextAttemptAt = now, lastError = reason, leaseOwner = null, leaseToken = null)
        }
        return updated
    }

    private fun transition(effectKey: EffectKey, update: (ShiftCoverageOutboxRecord) -> ShiftCoverageOutboxRecord?): ShiftCoverageOutboxRecord? {
        val rejected = AtomicBoolean(false)
        val updated = records.computeIfPresent(effectKey) { _, current ->
            val next = update(current)
            if (next == null) {
                rejected.set(true)
                current
            } else next
        }
        return if (rejected.get()) null else updated
    }
}

/** provider delivery admission은 반드시 bounded queue를 통과합니다. */
class ShiftCoverageDeliveryQueue(capacity: Int = 8) {
    private val queue = ArrayBlockingQueue<EffectKey>(capacity)
    fun offer(effectKey: EffectKey): Boolean = queue.offer(effectKey)
    fun poll(): EffectKey? = queue.poll()
    fun size(): Int = queue.size
}

class ShiftCoverageOutboxWorker(
    private val store: ShiftCoverageOutboxStore,
    private val queue: ShiftCoverageDeliveryQueue,
) {
    fun admit(effectKey: EffectKey, requestId: String): Boolean {
        if (!queue.offer(effectKey)) return false
        store.enqueue(requestId, effectKey)
        return true
    }
}
