package io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireLt
import io.bluetape4k.support.requireNotEmpty
import io.bluetape4k.support.requireZeroOrPositiveNumber
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.EventHashMaterial
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.NewEvent
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.PersistedEvent
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.StreamKey
import io.bluetape4k.workshop.commerce.metering.eventsourcing.eventstore.CanonicalEventHash
import io.bluetape4k.workshop.commerce.metering.eventsourcing.eventstore.EventStore
import io.bluetape4k.workshop.commerce.metering.eventsourcing.eventstore.EventTypeQuery
import io.bluetape4k.workshop.commerce.metering.eventsourcing.eventstore.OptimisticConcurrencyException
import io.bluetape4k.workshop.commerce.metering.eventsourcing.eventstore.StreamAppend
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.stereotype.Repository
import java.time.Clock
import java.util.*
/**
 * EventStoreRepository은 사용량 계량·청구에서 사용하는 업무 객체입니다.
 * @property clock 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */


@Repository
class EventStoreRepository(
    /**
     * `clock`은 사용량 과금·정산에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val clock: Clock = Clock.systemUTC(),
): AppendOnlyEventSourcingExposedJdbcRepository<DomainEventEntity, UUID>(DomainEventEntity::class),
   EventStore {

    private companion object: KLogging() {
        /**
         * `MAX_EVENT_PAGE_SIZE`은 사용량 과금·정산에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
         *
         * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
         */
        const val MAX_EVENT_PAGE_SIZE = 1_000
    }

    /**
     * appendAll은 사용량 계량·청구 변경을 outbox·이벤트 계약으로 전달하되 commit 순서, partition key, 중복 소비 방지와 재전송 결과를
     * 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param appends 업무 처리에 필요한 appends 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    override fun appendAll(appends: List<StreamAppend>): List<PersistedEvent> {
        appends.requireNotEmpty("appends")
        require(appends.map(StreamAppend::stream).distinct().size == appends.size) { "duplicate_stream_append" }

        return appends
            .sortedBy(StreamAppend::stream)
            .flatMap { append ->
                append(append.stream, append.expectedVersion, append.events)
            }
    }

    /**
     * append은 사용량 계량·청구 변경을 outbox·이벤트 계약으로 전달하되 commit 순서, partition key, 중복 소비 방지와 재전송 결과를 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param stream 업무 처리에 필요한 stream 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param expectedVersion 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     * @param events 사용량 계량·청구 상태 변경을 나타내는 이벤트 또는 저장 레코드입니다. 순서·revision·중복 여부를 보존해야 합니다.
     */
    override fun append(stream: StreamKey, expectedVersion: Long, events: List<NewEvent>): List<PersistedEvent> {
        expectedVersion.requireZeroOrPositiveNumber("expectedVersion")
        events.requireNotEmpty("events")

        val now = clock.instant()
        val head = lockHead(stream, now)
        if (head.version != expectedVersion) {
            throw OptimisticConcurrencyException(stream, expectedVersion, head.version)
        }

        var previousHash = head.latestHash
        val appended = events
            .mapIndexed { index, event ->
                insertEvent(
                    stream = stream,
                    version = expectedVersion + index + 1L,
                    event = event,
                    previousHash = previousHash
                ).also { previousHash = it.eventHash }
            }
        updateHead(
            stream = stream,
            expectedVersion = expectedVersion,
            appendedCount = events.size,
            latestHash = previousHash,
            now = now
        )
        return appended
    }

    /**
     * lockHead은 사용량 계량·청구의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를 거절합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param stream 업무 처리에 필요한 stream 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    private fun lockHead(stream: StreamKey, now: java.time.Instant): LockedHead {
        EventStreamHeads.insertIgnore {
            it[id] = Uuid.V7.nextId()
            it[tenantId] = stream.tenantId
            it[streamType] = stream.streamType
            it[streamId] = stream.streamId
            it[streamVersion] = 0L
            it[latestEventHash] = null
            it[createdAt] = now
            it[updatedAt] = now
        }
        val head = EventStreamHeads
            .selectAll()
            .where {
                (EventStreamHeads.tenantId eq stream.tenantId) and
                        (EventStreamHeads.streamType eq stream.streamType) and
                        (EventStreamHeads.streamId eq stream.streamId)
            }
            .forUpdate()
            .single()

        return LockedHead(
            version = head[EventStreamHeads.streamVersion],
            latestHash = head[EventStreamHeads.latestEventHash]
        )
    }

    /**
     * insertEvent은 사용량 계량·청구 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param stream 업무 처리에 필요한 stream 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param version 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     * @param event 사용량 계량·청구 상태 변경을 나타내는 이벤트 또는 저장 레코드입니다. 순서·revision·중복 여부를 보존해야 합니다.
     * @param previousHash 업무 처리에 필요한 previousHash 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun insertEvent(
        stream: StreamKey,
        version: Long,
        event: NewEvent,
        previousHash: String?,
    ): PersistedEvent {
        val hash = CanonicalEventHash.sha256(
            EventHashMaterial(
                stream = stream,
                streamVersion = version,
                eventType = event.event.eventType,
                schemaVersion = event.event.schemaVersion,
                payload = event.payload,
                metadata = event.metadata,
                previousHash = previousHash,
            ),
        )

        val insert = DomainEvents.insert {
            it[id] = event.eventId
            it[tenantId] = stream.tenantId
            it[streamType] = stream.streamType
            it[streamId] = stream.streamId
            it[streamVersion] = version
            it[eventType] = event.event.eventType
            it[schemaVersion] = event.event.schemaVersion
            it[payload] = event.payload
            it[metadata] = event.metadata
            it[DomainEvents.previousHash] = previousHash
            it[eventHash] = hash
            it[occurredAt] = event.occurredAt
        }
        val recordedAt = DomainEvents
            .selectAll()
            .where { DomainEvents.id eq event.eventId }
            .single()[DomainEvents.recordedAt]

        return PersistedEvent(
            eventId = event.eventId,
            stream = stream,
            streamVersion = version,
            globalPosition = insert[DomainEvents.globalPosition],
            eventType = event.event.eventType,
            schemaVersion = event.event.schemaVersion,
            payload = event.payload,
            metadata = event.metadata,
            previousHash = previousHash,
            eventHash = hash,
            occurredAt = event.occurredAt,
            recordedAt = recordedAt,
        )
    }

    /**
     * updateHead은 사용량 계량·청구 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param stream 업무 처리에 필요한 stream 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param expectedVersion 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     * @param appendedCount 한 번에 처리할 상한입니다. backpressure와 운영 자원 한도를 넘지 않도록 적용합니다.
     * @param latestHash 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    private fun updateHead(
        stream: StreamKey,
        expectedVersion: Long,
        appendedCount: Int,
        latestHash: String?,
        now: java.time.Instant,
    ) {
        val updated = EventStreamHeads.update(
            where = {
                (EventStreamHeads.tenantId eq stream.tenantId) and
                        (EventStreamHeads.streamType eq stream.streamType) and
                        (EventStreamHeads.streamId eq stream.streamId) and
                        (EventStreamHeads.streamVersion eq expectedVersion)
            },
        ) {
            it[streamVersion] = expectedVersion + appendedCount
            it[latestEventHash] = latestHash
            it[updatedAt] = now
        }

        check(updated == 1) { "stream_head_fencing_failed" }
    }

    /**
     * load은 사용량 계량·청구의 commit된 source-of-truth를 읽어 호출자에게 반환하며, 알림이나 캐시만으로 현재 상태를 추정하지 않습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param stream 업무 처리에 필요한 stream 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param afterVersion 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     */
    override fun load(stream: StreamKey, afterVersion: Long): List<PersistedEvent> =
        DomainEvents
            .selectAll()
            .where {
                (DomainEvents.tenantId eq stream.tenantId) and
                        (DomainEvents.streamType eq stream.streamType) and
                        (DomainEvents.streamId eq stream.streamId) and
                        (DomainEvents.streamVersion greater afterVersion)
            }
            .orderBy(DomainEvents.streamVersion to SortOrder.ASC)
            .map { row ->
                PersistedEvent(
                    eventId = row[DomainEvents.id].value,
                    stream = stream,
                    streamVersion = row[DomainEvents.streamVersion],
                    globalPosition = row[DomainEvents.globalPosition],
                    eventType = row[DomainEvents.eventType],
                    schemaVersion = row[DomainEvents.schemaVersion],
                    payload = row[DomainEvents.payload],
                    metadata = row[DomainEvents.metadata],
                    previousHash = row[DomainEvents.previousHash],
                    eventHash = row[DomainEvents.eventHash],
                    occurredAt = row[DomainEvents.occurredAt],
                    recordedAt = row[DomainEvents.recordedAt],
                )
            }

    /**
     * latestGlobalPosition은 사용량 계량·청구 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를
     * 함께 보존합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    override fun latestGlobalPosition(): Long =
        DomainEvents
            .selectAll()
            .orderBy(DomainEvents.globalPosition to SortOrder.DESC)
            .limit(1)
            .singleOrNull()
            ?.get(DomainEvents.globalPosition)
            ?: 0L

    /**
     * loadAfterGlobalPosition은 사용량 계량·청구의 commit된 source-of-truth를 읽어 호출자에게 반환하며, 알림이나 캐시만으로 현재
     * 상태를 추정하지
     * 않습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param afterPosition 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     * @param limit 한 번에 처리할 상한입니다. backpressure와 운영 자원 한도를 넘지 않도록 적용합니다.
     */
    override fun loadAfterGlobalPosition(afterPosition: Long, limit: Int): List<PersistedEvent> {
        afterPosition.requireZeroOrPositiveNumber("afterPosition")
        limit.requireInRange(1, MAX_EVENT_PAGE_SIZE, "limit")

        return DomainEvents
            .selectAll()
            .where { DomainEvents.globalPosition greater afterPosition }
            .orderBy(DomainEvents.globalPosition to SortOrder.ASC)
            .limit(limit)
            .map { row ->
                val stream = StreamKey(
                    tenantId = row[DomainEvents.tenantId],
                    streamType = row[DomainEvents.streamType],
                    streamId = row[DomainEvents.streamId],
                )
                PersistedEvent(
                    eventId = row[DomainEvents.id].value,
                    stream = stream,
                    streamVersion = row[DomainEvents.streamVersion],
                    globalPosition = row[DomainEvents.globalPosition],
                    eventType = row[DomainEvents.eventType],
                    schemaVersion = row[DomainEvents.schemaVersion],
                    payload = row[DomainEvents.payload],
                    metadata = row[DomainEvents.metadata],
                    previousHash = row[DomainEvents.previousHash],
                    eventHash = row[DomainEvents.eventHash],
                    occurredAt = row[DomainEvents.occurredAt],
                    recordedAt = row[DomainEvents.recordedAt],
                )
            }
    }

    /**
     * loadByType은 사용량 계량·청구의 commit된 source-of-truth를 읽어 호출자에게 반환하며, 알림이나 캐시만으로 현재 상태를 추정하지 않습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param query 업무 처리에 필요한 query 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    override fun loadByType(query: EventTypeQuery): List<PersistedEvent> {
        require(query.startsAt < query.endsAt) { "event_range_invalid" }
        query.startsAt.requireLt(query.endsAt) { "query.startsAt must be less than endsAt" }
        query.limit.requireInRange(1, MAX_EVENT_PAGE_SIZE, "query.limit")

        val cursor = query.after?.let {
            (DomainEvents.occurredAt greater it.occurredAt) or
                    ((DomainEvents.occurredAt eq it.occurredAt) and
                            (DomainEvents.id greater it.eventId))
        }
        return DomainEvents.selectAll()
            .where {
                (DomainEvents.tenantId eq query.tenantId) and
                        (DomainEvents.eventType eq query.eventType) and
                        (DomainEvents.occurredAt greaterEq query.startsAt) and
                        (DomainEvents.occurredAt less query.endsAt) and
                        (cursor ?: Op.TRUE)
            }
            .orderBy(
                DomainEvents.occurredAt to SortOrder.ASC,
                DomainEvents.id to SortOrder.ASC,
            )
            .limit(query.limit)
            .map(::toPersistedEvent)
    }

    /**
     * toPersistedEvent은 사용량 계량·청구 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께
     * 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param row 업무 처리에 필요한 row 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun toPersistedEvent(row: org.jetbrains.exposed.v1.core.ResultRow): PersistedEvent {
        val stream = StreamKey(
            tenantId = row[DomainEvents.tenantId],
            streamType = row[DomainEvents.streamType],
            streamId = row[DomainEvents.streamId]
        )

        return PersistedEvent(
            eventId = row[DomainEvents.id].value,
            stream = stream,
            streamVersion = row[DomainEvents.streamVersion],
            globalPosition = row[DomainEvents.globalPosition],
            eventType = row[DomainEvents.eventType],
            schemaVersion = row[DomainEvents.schemaVersion],
            payload = row[DomainEvents.payload],
            metadata = row[DomainEvents.metadata],
            previousHash = row[DomainEvents.previousHash],
            eventHash = row[DomainEvents.eventHash],
            occurredAt = row[DomainEvents.occurredAt],
            recordedAt = row[DomainEvents.recordedAt],
        )
    }
/**
 * LockedHead은 사용량 계량·청구에서 전달되는 불변 데이터 모델입니다.
 * @property version 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property latestHash 무결성·인증·멱등성 검증에 사용하는 보안 자료입니다. 원문 비밀값을 로그나 일반 응답에 노출하지 않고 변조 여부를 확인합니다.
 */


    private data class LockedHead(
        /**
         * `version`은 사용량 과금·정산에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
         *
         * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
         */
        val version: Long,
        /**
         * `latestHash`은 사용량 과금·정산에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
         *
         * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
         */
        val latestHash: String?,
    )
}
