package io.bluetape4k.workshop.commerce.voucher.eventsourced.operations

import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.support.requireZeroOrPositiveNumber
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.StreamKey
import java.time.Duration
import java.util.LinkedHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private const val KIBIBYTE = 1024
private const val SNAPSHOT_EVERY_EVENTS = 250
private const val MAX_REPLAY_EVENTS = 10_000
private const val MAX_REPLAY_SECONDS = 2L
private const val PROJECTION_BATCH_EVENTS = 200
private const val PROJECTION_TRANSACTION_SECONDS = 2L
private const val REBUILD_MAX_EVENTS = 100_000
private const val REBUILD_MAX_MINUTES = 10L
private const val REBUILD_THROTTLE_LAG_EVENTS = 10_000L
private const val REBUILD_THROTTLE_FOREGROUND_RATIO = 0.8
private const val MAINTENANCE_QUEUE_CAPACITY = 64
private const val MAINTENANCE_BATCH_ROWS = 100
private const val MAINTENANCE_TRANSACTION_SECONDS = 2L
private const val MAINTENANCE_MIN_BACKOFF_MILLIS = 100L
private const val MAINTENANCE_MAX_BACKOFF_SECONDS = 5L

/**
 * One immutable authority for correctness and dedicated performance profile limits.
 *
 * Environment-sensitive throughput and percentile targets remain stress-test assertions;
 * runtime safety limits are shared by normal and stress profiles.
 */
internal class EventSourcedRuntimeBudget {
    val snapshotEveryEvents: Int = SNAPSHOT_EVERY_EVENTS
    val maxReplayEvents: Int = MAX_REPLAY_EVENTS
    val maxReplayDuration: Duration = Duration.ofSeconds(MAX_REPLAY_SECONDS)
    val projectionBatchEvents: Int = PROJECTION_BATCH_EVENTS
    val projectionBatchBytes: Int = 2 * KIBIBYTE * KIBIBYTE
    val projectionTransactionTimeout: Duration = Duration.ofSeconds(PROJECTION_TRANSACTION_SECONDS)
    val rebuildBatchEvents: Int = PROJECTION_BATCH_EVENTS
    val rebuildMaxEvents: Int = REBUILD_MAX_EVENTS
    val rebuildMaxDuration: Duration = Duration.ofMinutes(REBUILD_MAX_MINUTES)
    val rebuildThrottleLagEvents: Long = REBUILD_THROTTLE_LAG_EVENTS
    val rebuildThrottleForegroundRatio: Double = REBUILD_THROTTLE_FOREGROUND_RATIO
    val maintenanceQueueCapacity: Int = MAINTENANCE_QUEUE_CAPACITY
    val maintenanceBatchRows: Int = MAINTENANCE_BATCH_ROWS
    val maintenanceBatchBytes: Int = 2 * KIBIBYTE * KIBIBYTE
    val maintenanceTransactionTimeout: Duration = Duration.ofSeconds(MAINTENANCE_TRANSACTION_SECONDS)
    val maintenanceMinBackoff: Duration = Duration.ofMillis(MAINTENANCE_MIN_BACKOFF_MILLIS)
    val maintenanceMaxBackoff: Duration = Duration.ofSeconds(MAINTENANCE_MAX_BACKOFF_SECONDS)

    fun shouldThrottleRebuild(
        lagEvents: Long,
        foregroundActive: Int,
        foregroundCapacity: Int,
    ): Boolean {
        val validLag = lagEvents.requireZeroOrPositiveNumber("lagEvents")
        val validActive = foregroundActive.requireZeroOrPositiveNumber("foregroundActive")
        val validCapacity = foregroundCapacity.requirePositiveNumber("foregroundCapacity")
        val saturation = validActive.toDouble() / validCapacity
        return validLag >= rebuildThrottleLagEvents || saturation >= rebuildThrottleForegroundRatio
    }

    fun acceptsReplay(
        events: Int,
        duration: Duration,
    ): Boolean =
        events.requireZeroOrPositiveNumber("events") <= maxReplayEvents &&
            duration.requireZeroOrPositiveDuration("duration") <= maxReplayDuration

    fun acceptsProjectionBatch(
        events: Int,
        bytes: Int,
        duration: Duration,
    ): Boolean =
        events.requireZeroOrPositiveNumber("events") <= projectionBatchEvents &&
            bytes.requireZeroOrPositiveNumber("bytes") <= projectionBatchBytes &&
            duration.requireZeroOrPositiveDuration("duration") <= projectionTransactionTimeout

    fun acceptsRebuild(
        events: Int,
        duration: Duration,
    ): Boolean =
        events.requireZeroOrPositiveNumber("events") <= rebuildMaxEvents &&
            duration.requireZeroOrPositiveDuration("duration") <= rebuildMaxDuration

    fun acceptsMaintenanceBatch(
        rows: Int,
        bytes: Int,
        duration: Duration,
    ): Boolean =
        rows.requireZeroOrPositiveNumber("rows") <= maintenanceBatchRows &&
            bytes.requireZeroOrPositiveNumber("bytes") <= maintenanceBatchBytes &&
            duration.requireZeroOrPositiveDuration("duration") <= maintenanceTransactionTimeout

    fun maintenanceBackoff(attempt: Int): Duration {
        val validAttempt = attempt.requireZeroOrPositiveNumber("attempt")
        val multiplier = 1L shl validAttempt.coerceAtMost(MAX_BACKOFF_SHIFT)
        return maintenanceMinBackoff.multipliedBy(multiplier).coerceAtMost(maintenanceMaxBackoff)
    }

    private companion object {
        private const val MAX_BACKOFF_SHIFT = 16
    }
}

@ConsistentCopyVisibility
internal data class SnapshotMaintenanceRequest private constructor(
    val stream: StreamKey,
    val streamVersion: Long,
) {
    companion object {
        operator fun invoke(
            stream: StreamKey,
            streamVersion: Long,
        ): SnapshotMaintenanceRequest =
            SnapshotMaintenanceRequest(
                stream = stream,
                streamVersion = streamVersion.requirePositiveNumber("streamVersion"),
            )
    }
}

internal enum class MaintenanceOffer {
    ENQUEUED,
    COALESCED,
    REJECTED,
}

/**
 * Bounded stream-coalescing queue. A newer request for an existing stream replaces stale work
 * without consuming another slot; distinct work beyond capacity fails fast.
 */
internal class SnapshotMaintenanceQueue(
    capacity: Int = EventSourcedRuntimeBudget().maintenanceQueueCapacity,
) {
    private val capacity = capacity.requirePositiveNumber("capacity")
    private val lock = ReentrantLock()
    private val requests = LinkedHashMap<StreamKey, SnapshotMaintenanceRequest>()

    fun offer(request: SnapshotMaintenanceRequest): MaintenanceOffer =
        lock.withLock {
            val existing = requests[request.stream]
            when {
                existing != null -> {
                    if (request.streamVersion > existing.streamVersion) {
                        requests[request.stream] = request
                    }
                    MaintenanceOffer.COALESCED
                }

                requests.size >= capacity -> MaintenanceOffer.REJECTED
                else -> {
                    requests[request.stream] = request
                    MaintenanceOffer.ENQUEUED
                }
            }
        }

    fun poll(): SnapshotMaintenanceRequest? =
        lock.withLock {
            val next = requests.entries.firstOrNull() ?: return@withLock null
            requests.remove(next.key)
        }

    fun size(): Int = lock.withLock(requests::size)
}

private fun Duration.requireZeroOrPositiveDuration(name: String): Duration {
    check(!isNegative) { "$name must not be negative" }
    return this
}

private fun Duration.coerceAtMost(maximum: Duration): Duration = if (this <= maximum) this else maximum
