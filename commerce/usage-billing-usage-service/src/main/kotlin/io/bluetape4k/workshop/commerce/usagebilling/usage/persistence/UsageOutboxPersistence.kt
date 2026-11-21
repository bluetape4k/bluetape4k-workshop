@file:Suppress("MagicNumber") // SQL column sizes are schema declarations.

package io.bluetape4k.workshop.commerce.usagebilling.usage.persistence

import io.bluetape4k.exposed.core.dao.id.TimebasedUUIDTable
import io.bluetape4k.exposed.dao.id.TimebasedUUIDEntity
import io.bluetape4k.exposed.dao.id.TimebasedUUIDEntityClass
import io.bluetape4k.exposed.dao.id.TimebasedUUIDEntityID
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.spring.data.exposed.jdbc.repository.ExposedJdbcRepository
import io.bluetape4k.spring.data.exposed.jdbc.repository.support.ExposedEntityInformationImpl
import io.bluetape4k.spring.data.exposed.jdbc.repository.support.SimpleExposedJdbcRepository
import io.bluetape4k.workshop.commerce.usagebilling.usage.domain.PriceEvidence
import io.bluetape4k.workshop.commerce.usagebilling.usage.domain.PriceEvidenceInboxEvent
import io.bluetape4k.workshop.commerce.usagebilling.usage.domain.PriceEvidenceInboxOutcome
import io.bluetape4k.workshop.commerce.usagebilling.usage.domain.UsageAcceptanceJournal
import io.bluetape4k.workshop.commerce.usagebilling.usage.domain.UsageOutboxRecord
import io.bluetape4k.workshop.commerce.usagebilling.usage.domain.UsageRecord
import io.bluetape4k.workshop.commerce.usagebilling.usage.messaging.UsageOutboxJournal
import io.bluetape4k.workshop.commerce.usagebilling.usage.messaging.UsageOutboxLease
import io.bluetape4k.workshop.commerce.usagebilling.usage.messaging.UsageOutboxStatus
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.dao.Entity
import org.jetbrains.exposed.v1.javatime.timestamp
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.*
import kotlin.reflect.KClass

/**
 * UsageOutboxEvents은 사용량 과금 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
object UsageOutboxEvents: TimebasedUUIDTable("usage_outbox_event", "outbox_event_id") {
    /**
     * 업무 추적에 사용하는 불변 식별자입니다. 재시도와 비동기 전달에서도 같은 값을 유지합니다.
     */
    val eventId = javaUUID("event_id").uniqueIndex()
    /**
     * 사용량 계량·청구 요청이 속한 테넌트 식별자입니다. 저장·이벤트·멱등성 범위를 이 값으로 분리합니다.
     */
    val tenantId = varchar("tenant_id", 64)
    /**
     * 사용량 계량·청구의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
     */
    val eventType = varchar("event_type", 128)
    /**
     * 사용량 계량·청구의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
     */
    val aggregateType = varchar("aggregate_type", 64)
    /**
     * `aggregateId`은 사용량 과금·정산에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val aggregateId = varchar("aggregate_id", 128)
    /**
     * 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값이 다르면 오래된 명령을 거부합니다.
     */
    val aggregateVersion = long("aggregate_version")
    /**
     * 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
     */
    val partitionKey = varchar("partition_key", 256)
    /**
     * 사용량 계량·청구 경계의 전달 데이터입니다. 스키마·크기·무결성을 검증한 뒤 저장하거나 발행합니다.
     */
    val payload = text("payload")
    /**
     * 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
     */
    val payloadDigest = varchar("payload_digest", 64)
    /**
     * 사용량 계량·청구의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
     */
    val status = varchar("status", 32)
    /**
     * `attempt`은 사용량 과금·정산에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val attempt = integer("attempt").default(0)
    /**
     * 사용량 계량·청구의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    val nextAttemptAt = timestamp("next_attempt_at").nullable()
    /**
     * 사용량 계량·청구 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    val claimOwner = varchar("claim_owner", 128).nullable()
    /**
     * 사용량 계량·청구의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    val claimUntil = timestamp("claim_until").nullable()
    /**
     * 사용량 계량·청구의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    val createdAt = timestamp("created_at")
    /**
     * 사용량 계량·청구의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    val updatedAt = timestamp("updated_at")

    init {
        index(false, status, nextAttemptAt, id)
    }
}

/**
 * UsagePriceEvidence은 사용량 과금 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
object UsagePriceEvidence: TimebasedUUIDTable("usage_price_evidence", "price_evidence_id") {
    /**
     * 사용량 계량·청구 요청이 속한 테넌트 식별자입니다. 저장·이벤트·멱등성 범위를 이 값으로 분리합니다.
     */
    val tenantId = varchar("tenant_id", 64)
    /**
     * 사용량 계량·청구의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
     */
    val meterCode = varchar("meter_code", 64)
    /**
     * 청구·정산 금액 또는 통화입니다. 통화 일치와 반올림 규칙을 적용한 뒤 저장합니다.
     */
    val currency = varchar("currency", 3)
    /**
     * 청구·정산 금액 또는 통화입니다. 통화 일치와 반올림 규칙을 적용한 뒤 저장합니다.
     */
    val unitPrice = decimal("unit_price", 19, 6)
    /**
     * 사용량 계량·청구의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    val effectiveAt = timestamp("effective_at")

    init {
        uniqueIndex(tenantId, meterCode, currency, effectiveAt)
    }
}

/**
 * UsagePriceEvidenceInboxEvents은 사용량 과금 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의
 * 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
object UsagePriceEvidenceInboxEvents:
    TimebasedUUIDTable("usage_price_evidence_inbox_event", "price_evidence_inbox_event_id") {
    /**
     * 업무 추적에 사용하는 불변 식별자입니다. 재시도와 비동기 전달에서도 같은 값을 유지합니다.
     */
    val eventId = javaUUID("event_id")
    /**
     * 사용량 계량·청구 요청이 속한 테넌트 식별자입니다. 저장·이벤트·멱등성 범위를 이 값으로 분리합니다.
     */
    val tenantId = varchar("tenant_id", 64)
    /**
     * 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
     */
    val payloadDigest = varchar("payload_digest", 64)
    /**
     * 사용량 계량·청구의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    val createdAt = timestamp("created_at")

    init {
        uniqueIndex(tenantId, eventId)
    }
}

/**
 * UsageRecords은 사용량 과금 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
object UsageRecords: TimebasedUUIDTable("usage_record", "usage_id") {
    /**
     * 사용량 계량·청구 요청이 속한 테넌트 식별자입니다. 저장·이벤트·멱등성 범위를 이 값으로 분리합니다.
     */
    val tenantId = varchar("tenant_id", 64)
    /**
     * 사용량 계량·청구 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    val sourceSystem = varchar("source_system", 64)
    /**
     * `sourceEventId`은 사용량 과금·정산에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val sourceEventId = varchar("source_event_id", 128)
    /**
     * 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
     */
    val fingerprint = varchar("fingerprint", 256)
    /**
     * 업무 추적에 사용하는 불변 식별자입니다. 재시도와 비동기 전달에서도 같은 값을 유지합니다.
     */
    val eventId = javaUUID("event_id")
    /**
     * 사용량 계량·청구의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
     */
    val meterCode = varchar("meter_code", 64)
    /**
     * 청구·정산 금액 또는 통화입니다. 통화 일치와 반올림 규칙을 적용한 뒤 저장합니다.
     */
    val currency = varchar("currency", 3)
    /**
     * `quantity`은 사용량 과금·정산에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val quantity = decimal("quantity", 19, 6)
    /**
     * 청구·정산 금액 또는 통화입니다. 통화 일치와 반올림 규칙을 적용한 뒤 저장합니다.
     */
    val unitPrice = decimal("unit_price", 19, 6)
    /**
     * 사용량 계량·청구의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    val occurredAt = timestamp("occurred_at")

    init {
        uniqueIndex(tenantId, sourceSystem, sourceEventId)
    }
}

/**
 * UsageOutboxEventEntity은 사용량 과금 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
class UsageOutboxEventEntity(id: TimebasedUUIDEntityID): TimebasedUUIDEntity(id) {
    companion object: TimebasedUUIDEntityClass<UsageOutboxEventEntity>(UsageOutboxEvents)
    /**
     * 업무 추적에 사용하는 불변 식별자입니다. 재시도와 비동기 전달에서도 같은 값을 유지합니다.
     */

    var eventId by UsageOutboxEvents.eventId
    /**
     * 사용량 계량·청구 요청이 속한 테넌트 식별자입니다. 저장·이벤트·멱등성 범위를 이 값으로 분리합니다.
     */
    var tenantId by UsageOutboxEvents.tenantId
    /**
     * 사용량 계량·청구의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
     */
    var eventType by UsageOutboxEvents.eventType
    /**
     * 사용량 계량·청구의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
     */
    var aggregateType by UsageOutboxEvents.aggregateType
    /**
     * `aggregateId`은 사용량 과금·정산에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    var aggregateId by UsageOutboxEvents.aggregateId
    /**
     * 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값이 다르면 오래된 명령을 거부합니다.
     */
    var aggregateVersion by UsageOutboxEvents.aggregateVersion
    /**
     * 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
     */
    var partitionKey by UsageOutboxEvents.partitionKey
    /**
     * 사용량 계량·청구 경계의 전달 데이터입니다. 스키마·크기·무결성을 검증한 뒤 저장하거나 발행합니다.
     */
    var payload by UsageOutboxEvents.payload
    /**
     * 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
     */
    var payloadDigest by UsageOutboxEvents.payloadDigest
    /**
     * 사용량 계량·청구의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
     */
    var status by UsageOutboxEvents.status
    /**
     * `attempt`은 사용량 과금·정산에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    var attempt by UsageOutboxEvents.attempt
    /**
     * 사용량 계량·청구의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    var nextAttemptAt by UsageOutboxEvents.nextAttemptAt
    /**
     * 사용량 계량·청구 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    var claimOwner by UsageOutboxEvents.claimOwner
    /**
     * 사용량 계량·청구의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    var claimUntil by UsageOutboxEvents.claimUntil
    /**
     * 사용량 계량·청구의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    var createdAt by UsageOutboxEvents.createdAt
    /**
     * 사용량 계량·청구의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    var updatedAt by UsageOutboxEvents.updatedAt
}

/**
 * UsagePriceEvidenceEntity은 사용량 과금 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
class UsagePriceEvidenceEntity(id: TimebasedUUIDEntityID): TimebasedUUIDEntity(id) {
    companion object: TimebasedUUIDEntityClass<UsagePriceEvidenceEntity>(UsagePriceEvidence)
    /**
     * 사용량 계량·청구 요청이 속한 테넌트 식별자입니다. 저장·이벤트·멱등성 범위를 이 값으로 분리합니다.
     */

    var tenantId by UsagePriceEvidence.tenantId
    /**
     * 사용량 계량·청구의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
     */
    var meterCode by UsagePriceEvidence.meterCode
    /**
     * 청구·정산 금액 또는 통화입니다. 통화 일치와 반올림 규칙을 적용한 뒤 저장합니다.
     */
    var currency by UsagePriceEvidence.currency
    /**
     * 청구·정산 금액 또는 통화입니다. 통화 일치와 반올림 규칙을 적용한 뒤 저장합니다.
     */
    var unitPrice by UsagePriceEvidence.unitPrice
    /**
     * 사용량 계량·청구의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    var effectiveAt by UsagePriceEvidence.effectiveAt
}

/**
 * UsagePriceEvidenceInboxEventEntity은 사용량 과금 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이
 * 타입의
 * 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
class UsagePriceEvidenceInboxEventEntity(id: TimebasedUUIDEntityID): TimebasedUUIDEntity(id) {
    companion object: TimebasedUUIDEntityClass<UsagePriceEvidenceInboxEventEntity>(UsagePriceEvidenceInboxEvents)
    /**
     * 업무 추적에 사용하는 불변 식별자입니다. 재시도와 비동기 전달에서도 같은 값을 유지합니다.
     */

    var eventId by UsagePriceEvidenceInboxEvents.eventId
    /**
     * 사용량 계량·청구 요청이 속한 테넌트 식별자입니다. 저장·이벤트·멱등성 범위를 이 값으로 분리합니다.
     */
    var tenantId by UsagePriceEvidenceInboxEvents.tenantId
    /**
     * 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
     */
    var payloadDigest by UsagePriceEvidenceInboxEvents.payloadDigest
    /**
     * 사용량 계량·청구의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    var createdAt by UsagePriceEvidenceInboxEvents.createdAt
}

/**
 * UsageRecordEntity은 사용량 과금 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
class UsageRecordEntity(id: TimebasedUUIDEntityID): TimebasedUUIDEntity(id) {
    companion object: TimebasedUUIDEntityClass<UsageRecordEntity>(UsageRecords)
    /**
     * 사용량 계량·청구 요청이 속한 테넌트 식별자입니다. 저장·이벤트·멱등성 범위를 이 값으로 분리합니다.
     */

    var tenantId by UsageRecords.tenantId
    /**
     * 사용량 계량·청구 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    var sourceSystem by UsageRecords.sourceSystem
    /**
     * `sourceEventId`은 사용량 과금·정산에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    var sourceEventId by UsageRecords.sourceEventId
    /**
     * 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
     */
    var fingerprint by UsageRecords.fingerprint
    /**
     * 업무 추적에 사용하는 불변 식별자입니다. 재시도와 비동기 전달에서도 같은 값을 유지합니다.
     */
    var eventId by UsageRecords.eventId
    /**
     * 사용량 계량·청구의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
     */
    var meterCode by UsageRecords.meterCode
    /**
     * 청구·정산 금액 또는 통화입니다. 통화 일치와 반올림 규칙을 적용한 뒤 저장합니다.
     */
    var currency by UsageRecords.currency
    /**
     * `quantity`은 사용량 과금·정산에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    var quantity by UsageRecords.quantity
    /**
     * 청구·정산 금액 또는 통화입니다. 통화 일치와 반올림 규칙을 적용한 뒤 저장합니다.
     */
    var unitPrice by UsageRecords.unitPrice
    /**
     * 사용량 계량·청구의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    var occurredAt by UsageRecords.occurredAt
}

@Suppress("AbstractClassCanBeConcreteClass")
/**
 * UsageExposedJdbcRepository은 사용량 과금 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에
 * 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
abstract class UsageExposedJdbcRepository<E: Entity<ID>, ID: Any>(
    domainClass: KClass<E>,
): ExposedJdbcRepository<E, ID> by SimpleExposedJdbcRepository(ExposedEntityInformationImpl(domainClass.java))

@Suppress("AbstractClassCanBeConcreteClass")
/**
 * AppendOnlyUsageExposedJdbcRepository은 사용량 과금 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이
 * 타입의
 * 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
abstract class AppendOnlyUsageExposedJdbcRepository<E: Entity<ID>, ID: Any>(
    domainClass: KClass<E>,
): UsageExposedJdbcRepository<E, ID>(domainClass) {
    /**
     * save은 사용량 계량·청구 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param entity 업무 처리에 필요한 entity 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    final override fun <S: E> save(entity: S): S = immutableMutation()
    /**
     * saveAll은 사용량 계량·청구 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param entities 업무 처리에 필요한 entities 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    final override fun <S: E> saveAll(entities: Iterable<S>): List<S> = immutableMutation()
    /**
     * deleteById은 사용량 계량·청구의 취소·보상·종료 전이를 허용된 terminal 규칙에 따라 적용하고, 이미 처리된 요청은 멱등하게 유지합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param id 처리할 사용량 계량·청구 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    final override fun deleteById(id: ID): Unit = immutableMutation()
    /**
     * delete은 사용량 계량·청구의 취소·보상·종료 전이를 허용된 terminal 규칙에 따라 적용하고, 이미 처리된 요청은 멱등하게 유지합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param entity 업무 처리에 필요한 entity 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    final override fun delete(entity: E): Unit = immutableMutation()
    /**
     * deleteAllById은 사용량 계량·청구의 취소·보상·종료 전이를 허용된 terminal 규칙에 따라 적용하고, 이미 처리된 요청은 멱등하게 유지합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param ids 업무 처리에 필요한 ids 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    final override fun deleteAllById(ids: Iterable<ID>): Unit = immutableMutation()
    /**
     * deleteAll은 사용량 계량·청구의 취소·보상·종료 전이를 허용된 terminal 규칙에 따라 적용하고, 이미 처리된 요청은 멱등하게 유지합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param entities 업무 처리에 필요한 entities 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    final override fun deleteAll(entities: Iterable<E>): Unit = immutableMutation()
    /**
     * deleteAll은 사용량 계량·청구의 취소·보상·종료 전이를 허용된 terminal 규칙에 따라 적용하고, 이미 처리된 요청은 멱등하게 유지합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    final override fun deleteAll(): Unit = immutableMutation()

    /**
     * immutableMutation은 사용량 계량·청구 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께
     * 보존합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    protected fun <T> immutableMutation(): T = throw UnsupportedOperationException("append-only repository")
}

@Repository
/**
 * UsageOutboxRepository은 사용량 과금 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
class UsageOutboxRepository:
    AppendOnlyUsageExposedJdbcRepository<UsageOutboxEventEntity, UUID>(UsageOutboxEventEntity::class)

@Repository
/**
 * UsagePriceEvidenceRepository은 사용량 과금 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에
 * 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
class UsagePriceEvidenceRepository:
    AppendOnlyUsageExposedJdbcRepository<UsagePriceEvidenceEntity, UUID>(UsagePriceEvidenceEntity::class) {
    /**
     * append은 사용량 계량·청구 변경을 outbox·이벤트 계약으로 전달하되 commit 순서, partition key, 중복 소비 방지와 재전송 결과를 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param evidence 업무 처리에 필요한 evidence 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun append(evidence: PriceEvidence) {
        UsagePriceEvidenceEntity.new {
            tenantId = evidence.tenantId
            meterCode = evidence.meterCode
            currency = evidence.currency
            unitPrice = evidence.unitPrice
            effectiveAt = evidence.effectiveAt
        }
    }
}

@Repository
/**
 * UsagePriceEvidenceInboxRepository은 사용량 과금 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의
 * 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
class UsagePriceEvidenceInboxRepository:
    AppendOnlyUsageExposedJdbcRepository<UsagePriceEvidenceInboxEventEntity, UUID>(
        UsagePriceEvidenceInboxEventEntity::class,
    ) {
    /**
     * record은 사용량 계량·청구 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param event 사용량 계량·청구 상태 변경을 나타내는 이벤트 또는 저장 레코드입니다. 순서·revision·중복 여부를 보존해야 합니다.
     */
    fun record(event: PriceEvidenceInboxEvent): PriceEvidenceInboxOutcome {
        val inserted = UsagePriceEvidenceInboxEvents.insertIgnore {
            it[eventId] = event.eventId
            it[tenantId] = event.tenantId
            it[payloadDigest] = event.payloadDigest
            it[createdAt] = event.evidence.effectiveAt
        }.insertedCount == 1

        if (!inserted) {
            val existing = UsagePriceEvidenceInboxEvents.selectAll()
                .where {
                    (UsagePriceEvidenceInboxEvents.tenantId eq event.tenantId) and
                            (UsagePriceEvidenceInboxEvents.eventId eq event.eventId)
                }
                .singleOrNull()

            return when (existing?.get(UsagePriceEvidenceInboxEvents.payloadDigest)) {
                event.payloadDigest -> PriceEvidenceInboxOutcome.DUPLICATE
                else -> PriceEvidenceInboxOutcome.QUARANTINED
            }
        }
        UsagePriceEvidenceEntity.new {
            tenantId = event.evidence.tenantId
            meterCode = event.evidence.meterCode
            currency = event.evidence.currency
            unitPrice = event.evidence.unitPrice
            effectiveAt = event.evidence.effectiveAt
        }
        return PriceEvidenceInboxOutcome.APPLIED
    }
}

@Repository
/**
 * UsageRecordRepository은 사용량 과금 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
class UsageRecordRepository:
    AppendOnlyUsageExposedJdbcRepository<UsageRecordEntity, UUID>(UsageRecordEntity::class)

@Repository
/**
 * ExposedUsageAcceptanceJournal은 사용량 과금 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의
 * 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
class ExposedUsageAcceptanceJournal: UsageAcceptanceJournal {

    /**
     * priceEvidence은 사용량 계량·청구 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께
     * 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 사용량 계량·청구 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param meterCode 업무 처리에 필요한 meterCode 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param currency 업무 처리에 필요한 currency 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    override fun priceEvidence(tenantId: String, meterCode: String, currency: String): PriceEvidence? =
        UsagePriceEvidenceEntity
            .find {
                (UsagePriceEvidence.tenantId eq tenantId) and
                        (UsagePriceEvidence.meterCode eq meterCode) and
                        (UsagePriceEvidence.currency eq currency)
            }
            .firstOrNull()
            ?.let {
                PriceEvidence(
                    it.tenantId,
                    it.meterCode,
                    it.currency,
                    it.unitPrice,
                    it.effectiveAt
                )
            }

    /**
     * findUsage은 사용량 계량·청구의 commit된 source-of-truth를 읽어 호출자에게 반환하며, 알림이나 캐시만으로 현재 상태를 추정하지 않습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 사용량 계량·청구 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param sourceSystem 업무 처리에 필요한 sourceSystem 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param sourceEventId 처리할 사용량 계량·청구 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    override fun findUsage(tenantId: String, sourceSystem: String, sourceEventId: String): UsageRecord? =
        UsageRecordEntity
            .find {
                (UsageRecords.tenantId eq tenantId) and
                        (UsageRecords.sourceSystem eq sourceSystem) and
                        (UsageRecords.sourceEventId eq sourceEventId)
            }
            .firstOrNull()
            ?.let {
                UsageRecord(
                    it.id.value,
                    it.eventId,
                    it.tenantId,
                    it.sourceSystem,
                    it.sourceEventId,
                    it.fingerprint,
                    it.meterCode,
                    it.currency,
                    it.quantity,
                    it.unitPrice,
                    it.occurredAt,
                )
            }

    /**
     * append은 사용량 계량·청구 변경을 outbox·이벤트 계약으로 전달하되 commit 순서, partition key, 중복 소비 방지와 재전송 결과를 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param usage 업무 처리에 필요한 usage 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param outboxRecord 사용량 계량·청구 상태 변경을 나타내는 이벤트 또는 저장 레코드입니다. 순서·revision·중복 여부를 보존해야 합니다.
     */
    override fun append(usage: UsageRecord, outboxRecord: UsageOutboxRecord) {
        UsageRecordEntity.new(usage.usageId) {
            tenantId = usage.tenantId
            sourceSystem = usage.sourceSystem
            sourceEventId = usage.sourceEventId
            fingerprint = usage.fingerprint
            eventId = usage.eventId
            meterCode = usage.meterCode
            currency = usage.currency
            quantity = usage.quantity
            unitPrice = usage.unitPrice
            occurredAt = usage.occurredAt
        }
        UsageOutboxEventEntity.new {
            eventId = outboxRecord.eventId
            tenantId = usage.tenantId
            eventType = outboxRecord.eventType
            aggregateType = "Usage"
            aggregateId = usage.sourceEventId
            aggregateVersion = 1
            partitionKey = outboxRecord.partitionKey
            payload = outboxRecord.payload
            payloadDigest = outboxRecord.payloadDigest
            status = "PENDING"
            attempt = 0
            nextAttemptAt = null
            claimOwner = null
            claimUntil = null
            createdAt = outboxRecord.createdAt
            updatedAt = outboxRecord.createdAt
        }
    }
}

@Repository
/**
 * ExposedUsageOutboxJournal은 사용량 과금 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
class ExposedUsageOutboxJournal: UsageOutboxJournal {

    private companion object: KLogging() {
        /**
         * 재시도·임대·보존·처리 제한을 결정하는 시간 정책입니다. 장애 시 무한 대기와 중복 실행을 막습니다.
         */
        val CLAIM_LEASE: Duration = 30.seconds()
        /**
         * 재시도·임대·보존·처리 제한을 결정하는 시간 정책입니다. 장애 시 무한 대기와 중복 실행을 막습니다.
         */
        val RETRY_DELAY: Duration = 1.seconds()
        /**
         * `MAX_ATTEMPTS`은 사용량 과금·정산에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
         *
         * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
         */
        const val MAX_ATTEMPTS = 3
    }

    /**
     * claim은 사용량 계량·청구의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를 거절합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param owner 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param limit 한 번에 처리할 상한입니다. backpressure와 운영 자원 한도를 넘지 않도록 적용합니다.
     */
    @Transactional
    override fun claim(owner: String, now: Instant, limit: Int): List<UsageOutboxLease> =
        UsageOutboxEvents
            .selectAll()
            .where { claimableAt(now) }
            .orderBy(UsageOutboxEvents.createdAt to SortOrder.ASC, UsageOutboxEvents.id to SortOrder.ASC)
            .limit(limit)
            .mapNotNull { row ->
                /**
                 * `eventId`은 사용량 과금·정산에서 업무 대상의 불변 식별자입니다.
                 *
                 * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
                 */
                val eventId = row[UsageOutboxEvents.eventId]
                /**
                 * `claimed`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
                 *
                 * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
                 */
                val claimed = UsageOutboxEvents.update({
                    (UsageOutboxEvents.eventId eq eventId) and claimableAt(now)
                }) {
                    it[status] = UsageOutboxStatus.CLAIMED.name
                    it[claimOwner] = owner
                    it[claimUntil] = now.plus(CLAIM_LEASE)
                    it[updatedAt] = now
                }
                if (claimed != 1) return@mapNotNull null

                UsageOutboxEvents
                    .selectAll()
                    .where { UsageOutboxEvents.eventId eq eventId }
                    .singleOrNull()
                    ?.let { claimedRow ->
                        UsageOutboxLease(
                            eventId = claimedRow[UsageOutboxEvents.eventId],
                            partitionKey = claimedRow[UsageOutboxEvents.partitionKey],
                            payload = claimedRow[UsageOutboxEvents.payload],
                        )
                    }
            }

    /**
     * markPublished은 사용량 계량·청구 변경을 outbox·이벤트 계약으로 전달하되 commit 순서, partition key, 중복 소비 방지와 재전송
     * 결과를 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param eventId 처리할 사용량 계량·청구 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param owner 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    @Transactional
    override fun markPublished(eventId: UUID, owner: String, now: Instant): Boolean =
        UsageOutboxEvents.update({ claimedBy(owner, eventId, now) }) {
            it[status] = UsageOutboxStatus.PUBLISHED.name
            it[claimOwner] = null
            it[claimUntil] = null
            it[nextAttemptAt] = null
            it[updatedAt] = now
        } == 1

    /**
     * markRetryWait은 사용량 계량·청구의 실패·지연 데이터를 복구할 때 원본 revision과 중복 키를 확인하고, 재시도 가능한 항목만 안전하게 재처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param eventId 처리할 사용량 계량·청구 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param owner 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    @Transactional
    override fun markRetryWait(eventId: UUID, owner: String, now: Instant): Boolean =
        UsageOutboxEvents
            .selectAll()
            .where { claimedBy(owner, eventId, now) }
            .singleOrNull()
            ?.let { row ->
                /**
                 * `attempts`은 사용량 과금·정산에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
                 *
                 * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
                 */
                val attempts = row[UsageOutboxEvents.attempt] + 1
                UsageOutboxEvents.update({ claimedBy(owner, eventId, now) }) {
                    it[attempt] = attempts
                    it[status] = if (attempts >= MAX_ATTEMPTS) {
                        UsageOutboxStatus.QUARANTINED.name
                    } else {
                        UsageOutboxStatus.RETRY_WAIT.name
                    }
                    it[claimOwner] = null
                    it[claimUntil] = null
                    it[nextAttemptAt] = now.plus(RETRY_DELAY)
                    it[updatedAt] = now
                } == 1
            } ?: false

    /**
     * claimableAt은 사용량 계량·청구의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를 거절합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    private fun claimableAt(now: Instant): Op<Boolean> =
        (UsageOutboxEvents.status eq UsageOutboxStatus.PENDING.name) or
                ((UsageOutboxEvents.status eq UsageOutboxStatus.RETRY_WAIT.name) and
                        (UsageOutboxEvents.nextAttemptAt lessEq now)) or
                ((UsageOutboxEvents.status eq UsageOutboxStatus.CLAIMED.name) and
                        (UsageOutboxEvents.claimUntil lessEq now))

    /**
     * claimedBy은 사용량 계량·청구의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를 거절합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param owner 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     * @param eventId 처리할 사용량 계량·청구 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    private fun claimedBy(owner: String, eventId: UUID, now: Instant): Op<Boolean> =
        (UsageOutboxEvents.eventId eq eventId) and
                (UsageOutboxEvents.status eq UsageOutboxStatus.CLAIMED.name) and
                (UsageOutboxEvents.claimOwner eq owner) and
                (UsageOutboxEvents.claimUntil greater now)

}
