package io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence

import io.bluetape4k.support.requireEquals
import io.bluetape4k.support.requireNotEmpty
import io.bluetape4k.support.requireNotNull
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.EventEnvelope
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.EventPayload
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.StreamReference
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.TenantId
import io.bluetape4k.workshop.commerce.voucher.eventsourced.operations.EventSourcedDatabaseLane
import io.bluetape4k.workshop.commerce.voucher.eventsourced.operations.EventSourcedDatabasePermitGate
import io.bluetape4k.workshop.commerce.voucher.eventsourced.operations.EventSourcedPermitTransactionRunner
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Duration
import java.time.Instant
import java.util.*
import kotlin.time.TimeSource
import kotlin.time.toJavaDuration

/**
 * `EVENT_STORE_FENCE_ID`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
 *
 * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
 */
private val EVENT_STORE_FENCE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001")

/** foreground append ownership은 caller에게 남겨 두고 독립 read transaction을 엽니다. */
/** foreground append ownership은 caller에게 남겨 두고 독립 read transaction을 엽니다. */
internal interface EventStoreTransactionRunner {
    /**
     * readTransaction은 프로모션 바우처의 commit된 source-of-truth를 읽어 호출자에게 반환하며, 알림이나 캐시만으로 현재 상태를 추정하지
     * 않습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param block 업무 처리에 필요한 block 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun <T> readTransaction(block: () -> T): T
}
/**
 * ExposedEventStoreTransactionRunner은 프로모션 바우처에서 사용하는 업무 객체입니다.
 * @property database 프로모션 바우처 경계의 전달 데이터입니다. 스키마·크기·무결성을 검증한 뒤 저장하거나 발행합니다.
 */


internal class ExposedEventStoreTransactionRunner(
    /**
     * `database`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val database: Database,
): EventStoreTransactionRunner {
    /**
     * readTransaction은 프로모션 바우처의 commit된 source-of-truth를 읽어 호출자에게 반환하며, 알림이나 캐시만으로 현재 상태를 추정하지
     * 않습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param block 업무 처리에 필요한 block 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    override fun <T> readTransaction(block: () -> T): T =
        transaction(database) {
            block()
        }
}

/** production read는 HikariCP에 connection을 요청하기 전에 foreground permit을 획득합니다. */
/** production read는 HikariCP에 connection을 요청하기 전에 foreground permit을 획득합니다. */
internal class PermittedEventStoreTransactionRunner(
    database: Database,
    permits: EventSourcedDatabasePermitGate,
): EventStoreTransactionRunner {
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val transactions = EventSourcedPermitTransactionRunner(
        database = database,
        permits = permits,
        lane = EventSourcedDatabaseLane.FOREGROUND,
    )

    /**
     * readTransaction은 프로모션 바우처의 commit된 source-of-truth를 읽어 호출자에게 반환하며, 알림이나 캐시만으로 현재 상태를 추정하지
     * 않습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param block 업무 처리에 필요한 block 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    override fun <T> readTransaction(block: () -> T): T =
        transactions.inTransaction(block)
}

/**
 * PostgreSQL event authority입니다. append는 의도적으로 caller의 transaction을 사용해
 * command idempotency finalization과 event persistence가 하나의 commit boundary를 공유하게 합니다.
 * @property reads 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property metrics 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
@Suppress("TooManyFunctions")
internal class EventStoreRepository(
    /**
     * `reads`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val reads: EventStoreTransactionRunner,
    /**
     * `metrics`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val metrics: EventStoreAppendMetrics = EventStoreAppendMetrics.NONE,
): EventStorePort {

    /**
     * load은 프로모션 바우처의 commit된 source-of-truth를 읽어 호출자에게 반환하며, 알림이나 캐시만으로 현재 상태를 추정하지 않습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param read 업무 처리에 필요한 read 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    override fun load(read: EventStoreRead): EventPage =
        reads.readTransaction {
            loadInCurrentTransaction(read)
        }

    /**
     * loadInCurrentTransaction은 프로모션 바우처의 commit된 source-of-truth를 읽어 호출자에게 반환하며, 알림이나 캐시만으로 현재
     * 상태를 추정하지
     * 않습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param read 업무 처리에 필요한 read 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    override fun loadInCurrentTransaction(read: EventStoreRead): EventPage {
        TransactionManager.current()
        val committedHead = headVersion(read.stream)
        val events = EventLog
            .selectAll()
            .where {
                (EventLog.tenantId eq read.stream.tenantId.value) and
                        (EventLog.streamType eq read.stream.streamType) and
                        (EventLog.streamId eq read.stream.streamId) and
                        (EventLog.streamVersion greater read.afterVersion) and
                        (EventLog.streamVersion lessEq committedHead)
            }
            .orderBy(EventLog.streamVersion to SortOrder.ASC)
            .limit(read.limit)
            .map(::toEnvelope)

        return EventPage(events, committedHead)
    }

    /**
     * appendAll은 프로모션 바우처 변경을 outbox·이벤트 계약으로 전달하되 commit 순서, partition key, 중복 소비 방지와 재전송 결과를
     * 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param appends 업무 처리에 필요한 appends 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    override fun appendAll(appends: List<ExpectedAppend>): AppendResult {
        TransactionManager.current()
        val validAppends = appends.requireNotEmpty("appends").requireNotNull("appends")

        val ordered = validAppends.sortedBy(ExpectedAppend::stream)
        ordered.map(ExpectedAppend::stream).toSet().size.requireEquals(ordered.size, "uniqueStreams.size")
        val eventIds = ordered.flatMap { it.events }.map(EventToAppend::eventId)
        eventIds.toSet().size.requireEquals(eventIds.size, "uniqueEventIds.size")

        val streamHeadStarted = TimeSource.Monotonic.markNow()
        val heads = ordered.associate { append -> append.stream to lockHead(append.stream) }

        metrics.streamHeadWait(streamHeadStarted.elapsedNow().toJavaDuration())

        val conflict = ordered.firstOrNull { append -> heads.getValue(append.stream) != append.expectedVersion }
        if (conflict != null) {
            return AppendResult.Conflict(
                stream = conflict.stream,
                expectedVersion = conflict.expectedVersion,
                actualVersion = heads.getValue(conflict.stream),
            )
        }
        return appendCommitted(ordered, eventIds)
    }

    /**
     * appendCommitted은 프로모션 바우처 변경을 outbox·이벤트 계약으로 전달하되 commit 순서, partition key, 중복 소비 방지와 재전송
     * 결과를 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param ordered 업무 처리에 필요한 ordered 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param eventIds 프로모션 바우처 상태 변경을 나타내는 이벤트 또는 저장 레코드입니다. 순서·revision·중복 여부를 보존해야 합니다.
     */
    private fun appendCommitted(
        ordered: List<ExpectedAppend>,
        eventIds: List<UUID>,
    ): AppendResult {
        val appendFenceStarted = TimeSource.Monotonic.markNow()
        val firstPosition = lockFence()
        metrics.appendFenceWait(appendFenceStarted.elapsedNow().toJavaDuration())
        eventIds.firstOrNull(::eventAlreadyRecorded)?.let { return AppendResult.DuplicateEvent(it) }
        reserveGlobalPositions(firstPosition, eventIds.size)
        var globalPosition = firstPosition
        val now = Instant.now()
        ordered.forEach { append ->
            append.events.forEachIndexed { index, event ->
                val envelope = event.toEnvelope(append.stream, append.expectedVersion + index + 1L, globalPosition, now)
                EventLog.insert { row ->
                    row[EventLog.id] = envelope.eventId
                    row[EventLog.tenantId] = envelope.tenantId.value
                    row[EventLog.streamType] = envelope.stream.type
                    row[EventLog.streamId] = envelope.stream.id
                    row[EventLog.streamVersion] = envelope.stream.version
                    row[EventLog.globalPosition] = envelope.globalPosition
                    row[EventLog.eventType] = envelope.eventType
                    row[EventLog.schemaVersion] = envelope.schemaVersion
                    row[EventLog.occurredAt] = envelope.occurredAt
                    row[EventLog.recordedAt] = envelope.recordedAt
                    row[EventLog.correlationId] = envelope.correlationId
                    row[EventLog.causationId] = envelope.causationId
                    row[EventLog.actorSurrogate] = envelope.actorSurrogate
                    row[EventLog.actorHmacKeyVersion] = envelope.actorHmacKeyVersion
                    row[EventLog.payload] = envelope.payload.canonicalJson
                    row[EventLog.canonicalChecksum] = envelope.canonicalChecksum
                }
                globalPosition += 1
            }
            StreamHeads.update(where = { streamHeadPredicate(append.stream) }) { row ->
                row[StreamHeads.version] = append.nextVersion()
                row[StreamHeads.updatedAt] = now
            }
        }

        return AppendResult.Appended(
            finalVersions = ordered.associate { append -> append.stream to append.nextVersion() },
            firstGlobalPosition = firstPosition,
            lastGlobalPosition = globalPosition - 1,
        )
    }

    /**
     * lockHead은 프로모션 바우처의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를 거절합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param stream 업무 처리에 필요한 stream 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun lockHead(stream: StreamKey): Long {
        StreamHeads.insertIgnore { row ->
            row[StreamHeads.streamId] = stream.streamId
            row[StreamHeads.tenantId] = stream.tenantId.value
            row[StreamHeads.streamType] = stream.streamType
            row[StreamHeads.version] = 0
            row[StreamHeads.updatedAt] = Instant.now()
        }
        return StreamHeads
            .selectAll()
            .where { streamHeadPredicate(stream) }
            .forUpdate()
            .single()[StreamHeads.version]
    }

    /**
     * headVersion은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param stream 업무 처리에 필요한 stream 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun headVersion(stream: StreamKey): Long =
        StreamHeads
            .selectAll()
            .where { streamHeadPredicate(stream) }
            .singleOrNull()
            ?.get(StreamHeads.version)
            ?: 0

    /**
     * streamHeadPredicate은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를
     * 함께 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param stream 업무 처리에 필요한 stream 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun streamHeadPredicate(stream: StreamKey): Op<Boolean> =
        (StreamHeads.tenantId eq stream.tenantId.value) and
                (StreamHeads.streamType eq stream.streamType) and
                (StreamHeads.streamId eq stream.streamId)

    /**
     * eventAlreadyRecorded은 프로모션 바우처의 의존성·복구 상태를 계산해 트래픽을 받아도 되는지와 운영자에게 노출할 장애 수준을 구분합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param eventId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    private fun eventAlreadyRecorded(eventId: UUID): Boolean =
        EventLog.selectAll().where { EventLog.id eq eventId }.limit(1).any()

    /**
     * lockFence은 프로모션 바우처의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를 거절합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    private fun lockFence(): Long {
        AppendFences.insertIgnore { row ->
            row[AppendFences.id] = EVENT_STORE_FENCE_ID
            row[AppendFences.nextGlobalPosition] = 1
        }
        val fence = AppendFences
            .selectAll()
            .where { AppendFences.id eq EVENT_STORE_FENCE_ID }
            .forUpdate()
            .single()

        return fence[AppendFences.nextGlobalPosition]
    }

    /**
     * reserveGlobalPositions은 프로모션 바우처의 재고·할당량·금액을 검증한 뒤 한 번만 상태 전이시키고, 충돌·재시도·보상 결과를 감사 가능한 형태로
     * 남깁니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param firstPosition 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     * @param eventCount 프로모션 바우처 상태 변경을 나타내는 이벤트 또는 저장 레코드입니다. 순서·revision·중복 여부를 보존해야 합니다.
     */
    private fun reserveGlobalPositions(
        firstPosition: Long,
        eventCount: Int,
    ) {
        AppendFences.update(where = { AppendFences.id eq EVENT_STORE_FENCE_ID }) { row ->
            row[AppendFences.nextGlobalPosition] = firstPosition + eventCount
        }
    }

    /**
     * toEnvelope은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param stream 업무 처리에 필요한 stream 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param streamVersion 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     * @param globalPosition 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     * @param recordedAt 프로모션 바우처 상태 변경을 나타내는 이벤트 또는 저장 레코드입니다. 순서·revision·중복 여부를 보존해야 합니다.
     */
    private fun EventToAppend.toEnvelope(
        stream: StreamKey,
        streamVersion: Long,
        globalPosition: Long,
        recordedAt: Instant,
    ) =
        EventEnvelope(
            eventId = eventId,
            tenantId = stream.tenantId,
            stream = StreamReference(
                stream.streamType,
                stream.streamId,
                streamVersion,
            ),
            globalPosition = globalPosition,
            eventType = eventType,
            schemaVersion = schemaVersion,
            occurredAt = occurredAt,
            recordedAt = recordedAt,
            correlationId = correlationId,
            causationId = causationId,
            actorSurrogate = actorSurrogate,
            payload = payload,
            actorHmacKeyVersion = actorHmacKeyVersion,
        )

    /**
     * toEnvelope은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param row 업무 처리에 필요한 row 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun toEnvelope(row: ResultRow) = EventEnvelope(
        eventId = row[EventLog.id].value,
        tenantId = TenantId(row[EventLog.tenantId]),
        stream = StreamReference(
            row[EventLog.streamType],
            row[EventLog.streamId],
            row[EventLog.streamVersion],
        ),
        globalPosition = row[EventLog.globalPosition],
        eventType = row[EventLog.eventType],
        schemaVersion = row[EventLog.schemaVersion],
        occurredAt = row[EventLog.occurredAt],
        recordedAt = row[EventLog.recordedAt],
        correlationId = row[EventLog.correlationId],
        causationId = row[EventLog.causationId],
        actorSurrogate = row[EventLog.actorSurrogate],
        payload = EventPayload(row[EventLog.payload]),
        actorHmacKeyVersion = row[EventLog.actorHmacKeyVersion],
    )
}

/**
 * EventStoreAppendMetrics은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의
 * 규칙에
 * 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal interface EventStoreAppendMetrics {
    /**
     * streamHeadWait은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께
     * 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param duration 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun streamHeadWait(duration: Duration)

    /**
     * appendFenceWait은 프로모션 바우처 변경을 outbox·이벤트 계약으로 전달하되 commit 순서, partition key, 중복 소비 방지와 재전송
     * 결과를 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param duration 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun appendFenceWait(duration: Duration)

    companion object {
        val NONE = object: EventStoreAppendMetrics {
            /**
             * streamHeadWait은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사
             * 필드를 함께 보존합니다.
             *
             * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
             *
             * @param duration 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
             */
            override fun streamHeadWait(duration: Duration) = Unit
            /**
             * appendFenceWait은 프로모션 바우처 변경을 outbox·이벤트 계약으로 전달하되 commit 순서, partition key, 중복 소비
             * 방지와 재전송 결과를
             * 보존합니다.
             *
             * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
             *
             * @param duration 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
             */
            override fun appendFenceWait(duration: Duration) = Unit
        }
    }
}
