package io.bluetape4k.workshop.flow.event.aggregation

import io.bluetape4k.coroutines.flow.extensions.GroupItem
import io.bluetape4k.coroutines.flow.extensions.bufferUntilChanged
import io.bluetape4k.coroutines.flow.extensions.chunked
import io.bluetape4k.coroutines.flow.extensions.groupBy
import io.bluetape4k.coroutines.flow.extensions.log
import io.bluetape4k.coroutines.flow.extensions.scanWith
import io.bluetape4k.coroutines.flow.extensions.toGroupItems
import io.bluetape4k.coroutines.flow.extensions.windowed
import io.bluetape4k.coroutines.flow.extensions.zipWithNext
import io.bluetape4k.support.requireGe
import io.bluetape4k.support.requirePositiveNumber
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull

/**
 * Teachable in-memory order event aggregation pipelines.
 *
 * These functions are designed for finite or replay-bounded workshop streams.
 * Durable storage, replay offsets, outbox publishing, and exactly-once
 * processing are intentionally outside this module.
 */
class OrderEventAggregationPipeline {

    /**
     * Emits compact summaries from fixed-size event chunks.
     *
     * `chunked` emits `List<OrderEvent>` values, so memory use is bounded by
     * `chunkSize` plus summary allocation.
     */
    fun chunkedActivity(
        events: Flow<OrderEvent>,
        chunkSize: Int,
    ): Flow<OrderActivitySummary> {
        chunkSize.requirePositiveNumber("chunkSize")
        return events
            .chunked(chunkSize, partialWindow = true)
            .map(OrderActivitySummary::from)
    }

    /**
     * Emits overlapping rolling summaries.
     *
     * `windowed` allocates one list per emitted window. When `step < size`,
     * retained event references appear in multiple windows and partial tail
     * windows are emitted because this example opts into `partialWindow=true`.
     */
    fun rollingActivity(
        events: Flow<OrderEvent>,
        size: Int,
        step: Int,
    ): Flow<OrderActivitySummary> {
        size.requirePositiveNumber("size")
        step.requirePositiveNumber("step")
        size.requireGe(step, "size")
        return events
            .windowed(size = size, step = step, partialWindow = true)
            .map(OrderActivitySummary::from)
    }

    /**
     * Partitions a completed finite stream by order id.
     *
     * This demonstrates `groupBy` and `toGroupItems`; it is not a hot path for
     * unbounded ingestion. Each group is materialized into a list, and a
     * collector is opened for each distinct key so finite high-cardinality
     * replays do not block waiting for completed groups.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun groupedByOrder(events: Flow<OrderEvent>): Flow<GroupItem<String, OrderEvent>> =
        events
            .groupBy { it.orderId }
            .flatMapMerge(concurrency = Int.MAX_VALUE) { it.toGroupItems() }

    /**
     * Accumulates immutable read-model snapshots with `scanWith`.
     *
     * The first emitted item is the initial empty model. Each event creates a
     * new map snapshot for clarity; high-throughput projections should use a
     * bounded mutable internal state plus durable checkpoints.
     */
    fun readModels(events: Flow<OrderEvent>): Flow<OrderReadModel> =
        events.scanWith({ OrderReadModel.empty() }) { model, event -> model.apply(event) }

    /**
     * Collapses adjacent unchanged lifecycle statuses for one order.
     *
     * `bufferUntilChanged` retains one run until status changes or upstream
     * completes, then emits a copied list for that run.
     */
    fun statusRuns(
        events: Flow<OrderEvent>,
        orderId: String,
    ): Flow<OrderStatusRun> {
        val normalizedOrderId = normalizeOrderId(orderId)
        return readModels(events)
            .mapNotNull { it.orders[normalizedOrderId] }
            .filter { it.version > 0 }
            .bufferUntilChanged { it.status }
            .map(OrderStatusRun::from)
    }

    /**
     * Emits lifecycle transitions after unchanged status runs are collapsed.
     */
    fun transitions(
        events: Flow<OrderEvent>,
        orderId: String,
    ): Flow<OrderTransition> =
        statusRuns(events, orderId)
            .map { it.finalState }
            .zipWithNext(OrderTransition::from)
            .filter { it.previousStatus != it.currentStatus }

    /**
     * Emits sanitized audit entries and applies a debug-only Flow log hook.
     *
     * The log hook sees `OrderAuditEntry`, not raw events. Upstream exception
     * messages are not sanitized by this function, so examples use non-sensitive
     * failure text.
     */
    fun audit(events: Flow<OrderEvent>): Flow<OrderAuditEntry> = flow {
        var sequence = 0
        events.collect { event ->
            sequence += 1
            emit(OrderAuditEntry.from(sequence, event))
        }
    }.log("order-event-aggregation")

    private fun normalizeOrderId(orderId: String): String =
        OrderCreated(orderId, "normalization-probe", java.time.Instant.EPOCH).orderId

}
