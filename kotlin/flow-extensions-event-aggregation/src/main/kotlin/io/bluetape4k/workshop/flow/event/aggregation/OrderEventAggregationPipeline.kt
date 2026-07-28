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
 * 학습용 in-memory order event aggregation pipeline 입니다.
 *
 * 이 함수들은 유한하거나 replay 범위가 제한된 workshop stream 을 대상으로 설계되었습니다.
 * 내구 저장소, replay offset, outbox publishing, exactly-once 처리는 의도적으로 이 모듈 범위에서 제외합니다.
 */
class OrderEventAggregationPipeline {

    /**
     * 고정 크기 event chunk 에서 압축된 summary 를 방출합니다.
     *
     * `chunked` 는 `List<OrderEvent>` 값을 방출하므로 메모리 사용량은 `chunkSize` 와 summary 할당량으로 제한됩니다.
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
     * 서로 겹치는 rolling summary 를 방출합니다.
     *
     * `windowed` 는 방출되는 window 마다 list 하나를 할당합니다. `step < size` 인 경우 보존된 event 참조가 여러 window 에 나타나며, 이 예제는 `partialWindow=true` 를 사용하므로 마지막 partial window 도 방출합니다.
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
     * 완료된 finite stream 을 order id 기준으로 분할합니다.
     *
     * `groupBy` 와 `toGroupItems` 사용법을 보여주기 위한 예제이며, 무한 ingestion 의 hot path 가 아닙니다. 각 group 은 list 로 materialize 되고, distinct key 마다 collector 를 열어 finite high-cardinality replay 가 완료된 group 을 기다리며 막히지 않게 합니다.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun groupedByOrder(events: Flow<OrderEvent>): Flow<GroupItem<String, OrderEvent>> =
        events
            .groupBy { it.orderId }
            .flatMapMerge(concurrency = Int.MAX_VALUE) { it.toGroupItems() }

    /**
     * `scanWith` 로 immutable read-model snapshot 을 누적합니다.
     *
     * 처음 방출되는 항목은 초기 empty model 입니다. 설명을 명확히 하기 위해 event 마다 새 map snapshot 을 만들지만, high-throughput projection 은 제한된 mutable internal state 와 durable checkpoint 를 사용해야 합니다.
     */
    fun readModels(events: Flow<OrderEvent>): Flow<OrderReadModel> =
        events.scanWith({ OrderReadModel.empty() }) { model, event -> model.apply(event) }

    /**
     * 하나의 order 에서 인접한 동일 lifecycle status run 을 하나로 접습니다.
     *
     * `bufferUntilChanged` 는 status 가 바뀌거나 upstream 이 완료될 때까지 하나의 run 을 보관한 뒤, 해당 run 의 복사된 list 를 방출합니다.
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
     * 변경되지 않은 status run 을 접은 뒤 lifecycle transition 을 방출합니다.
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
     * 정제된 audit entry 를 방출하고 debug 전용 Flow log hook 을 적용합니다.
     *
     * log hook 은 raw event 가 아니라 `OrderAuditEntry` 를 봅니다. upstream exception message 는 이 함수가 정제하지 않으므로 예제에서는 민감하지 않은 실패 문구만 사용합니다.
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
