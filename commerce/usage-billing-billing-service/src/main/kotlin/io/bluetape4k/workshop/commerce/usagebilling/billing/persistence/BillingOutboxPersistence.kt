@file:Suppress("MagicNumber") // SQL column sizes are schema declarations.

package io.bluetape4k.workshop.commerce.usagebilling.billing.persistence

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.spring.data.exposed.jdbc.repository.ExposedJdbcRepository
import io.bluetape4k.spring.data.exposed.jdbc.repository.support.ExposedEntityInformationImpl
import io.bluetape4k.spring.data.exposed.jdbc.repository.support.SimpleExposedJdbcRepository
import io.bluetape4k.support.checkNotNull
import io.bluetape4k.support.requireNotNull
import io.bluetape4k.workshop.commerce.usagebilling.billing.domain.BillingAdjustmentCommand
import io.bluetape4k.workshop.commerce.usagebilling.billing.domain.BillingAdjustmentJournal
import io.bluetape4k.workshop.commerce.usagebilling.billing.domain.BillingAdjustmentOutcome
import io.bluetape4k.workshop.commerce.usagebilling.billing.domain.BillingInboxEvent
import io.bluetape4k.workshop.commerce.usagebilling.billing.domain.BillingInboxJournal
import io.bluetape4k.workshop.commerce.usagebilling.billing.domain.BillingPriceEvidence
import io.bluetape4k.workshop.commerce.usagebilling.billing.domain.BillingPriceEvidenceEvent
import io.bluetape4k.workshop.commerce.usagebilling.billing.domain.BillingPriceEvidenceOutcome
import io.bluetape4k.workshop.commerce.usagebilling.billing.integration.BillingIntegrationEnvelope
import io.bluetape4k.workshop.commerce.usagebilling.billing.messaging.BillingOutboxJournal
import io.bluetape4k.workshop.commerce.usagebilling.billing.messaging.BillingOutboxLease
import io.bluetape4k.workshop.commerce.usagebilling.billing.messaging.BillingOutboxStatus
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.dao.Entity
import org.jetbrains.exposed.v1.dao.java.UUIDEntity
import org.jetbrains.exposed.v1.dao.java.UUIDEntityClass
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
 * BillingOutboxEvents은 사용량 과금 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
object BillingOutboxEvents: UUIDTable("billing_outbox_event", "outbox_event_id") {
    /**
     * 업무 추적에 사용하는 불변 식별자입니다. 재시도와 비동기 전달에서도 같은 값을 유지합니다.
     */
    val eventId = javaUUID("event_id")
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
        uniqueIndex(eventId)
        index(false, status, nextAttemptAt, id)
    }
}

/**
 * BillingPricingEvidence은 사용량 과금 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
object BillingPricingEvidence: UUIDTable("billing_pricing_evidence", "pricing_evidence_id") {
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
 * BillingPriceEvidenceInboxEvents은 사용량 과금 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의
 * 규칙에
 * 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
object BillingPriceEvidenceInboxEvents: UUIDTable(
    "billing_price_evidence_inbox_event",
    "price_evidence_inbox_event_id",
) {
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
 * BillingInboxEvents은 사용량 과금 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
object BillingInboxEvents: UUIDTable("billing_inbox_event", "inbox_event_id") {
    /**
     * 업무 추적에 사용하는 불변 식별자입니다. 재시도와 비동기 전달에서도 같은 값을 유지합니다.
     */
    val eventId = javaUUID("event_id")
    /**
     * 사용량 계량·청구 요청이 속한 테넌트 식별자입니다. 저장·이벤트·멱등성 범위를 이 값으로 분리합니다.
     */
    val tenantId = varchar("tenant_id", 64)
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
    val payloadDigest = varchar("payload_digest", 64)
    /**
     * 사용량 계량·청구의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
     */
    val status = varchar("status", 32)
    /**
     * 사용량 계량·청구의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    val createdAt = timestamp("created_at")

    init {
        uniqueIndex(eventId)
        uniqueIndex(tenantId, aggregateId, aggregateVersion)
    }
}

/**
 * BillingCharges은 사용량 과금 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
object BillingCharges: UUIDTable("billing_charge", "charge_id") {
    /**
     * `sourceEventId`은 사용량 과금·정산에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val sourceEventId = javaUUID("source_event_id")
    /**
     * 사용량 계량·청구 요청이 속한 테넌트 식별자입니다. 저장·이벤트·멱등성 범위를 이 값으로 분리합니다.
     */
    val tenantId = varchar("tenant_id", 64)
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
     * 청구·정산 금액 또는 통화입니다. 통화 일치와 반올림 규칙을 적용한 뒤 저장합니다.
     */
    val currency = varchar("currency", 3)
    /**
     * 청구·정산 금액 또는 통화입니다. 통화 일치와 반올림 규칙을 적용한 뒤 저장합니다.
     */
    val unitPrice = decimal("unit_price", 19, 6)
    /**
     * `quantity`은 사용량 과금·정산에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val quantity = decimal("quantity", 19, 6)
    /**
     * 청구·정산 금액 또는 통화입니다. 통화 일치와 반올림 규칙을 적용한 뒤 저장합니다.
     */
    val amount = decimal("amount", 19, 6)
    /**
     * 사용량 계량·청구의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    val createdAt = timestamp("created_at")

    init {
        uniqueIndex(sourceEventId)
    }
}

/**
 * BillingOutboxEventEntity은 사용량 과금 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
class BillingOutboxEventEntity(id: EntityID<UUID>): UUIDEntity(id) {
    companion object: UUIDEntityClass<BillingOutboxEventEntity>(BillingOutboxEvents)
    /**
     * 업무 추적에 사용하는 불변 식별자입니다. 재시도와 비동기 전달에서도 같은 값을 유지합니다.
     */

    var eventId by BillingOutboxEvents.eventId
    /**
     * 사용량 계량·청구 요청이 속한 테넌트 식별자입니다. 저장·이벤트·멱등성 범위를 이 값으로 분리합니다.
     */
    var tenantId by BillingOutboxEvents.tenantId
    /**
     * 사용량 계량·청구의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
     */
    var eventType by BillingOutboxEvents.eventType
    /**
     * 사용량 계량·청구의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
     */
    var aggregateType by BillingOutboxEvents.aggregateType
    /**
     * `aggregateId`은 사용량 과금·정산에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    var aggregateId by BillingOutboxEvents.aggregateId
    /**
     * 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값이 다르면 오래된 명령을 거부합니다.
     */
    var aggregateVersion by BillingOutboxEvents.aggregateVersion
    /**
     * 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
     */
    var partitionKey by BillingOutboxEvents.partitionKey
    /**
     * 사용량 계량·청구 경계의 전달 데이터입니다. 스키마·크기·무결성을 검증한 뒤 저장하거나 발행합니다.
     */
    var payload by BillingOutboxEvents.payload
    /**
     * 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
     */
    var payloadDigest by BillingOutboxEvents.payloadDigest
    /**
     * 사용량 계량·청구의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
     */
    var status by BillingOutboxEvents.status
    /**
     * `attempt`은 사용량 과금·정산에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    var attempt by BillingOutboxEvents.attempt
    /**
     * 사용량 계량·청구의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    var nextAttemptAt by BillingOutboxEvents.nextAttemptAt
    /**
     * 사용량 계량·청구 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    var claimOwner by BillingOutboxEvents.claimOwner
    /**
     * 사용량 계량·청구의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    var claimUntil by BillingOutboxEvents.claimUntil
    /**
     * 사용량 계량·청구의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    var createdAt by BillingOutboxEvents.createdAt
    /**
     * 사용량 계량·청구의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    var updatedAt by BillingOutboxEvents.updatedAt
}

/**
 * BillingPricingEvidenceEntity은 사용량 과금 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에
 * 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
class BillingPricingEvidenceEntity(id: EntityID<UUID>): UUIDEntity(id) {
    companion object: UUIDEntityClass<BillingPricingEvidenceEntity>(BillingPricingEvidence)
    /**
     * 사용량 계량·청구 요청이 속한 테넌트 식별자입니다. 저장·이벤트·멱등성 범위를 이 값으로 분리합니다.
     */

    var tenantId by BillingPricingEvidence.tenantId
    /**
     * 사용량 계량·청구의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
     */
    var meterCode by BillingPricingEvidence.meterCode
    /**
     * 청구·정산 금액 또는 통화입니다. 통화 일치와 반올림 규칙을 적용한 뒤 저장합니다.
     */
    var currency by BillingPricingEvidence.currency
    /**
     * 청구·정산 금액 또는 통화입니다. 통화 일치와 반올림 규칙을 적용한 뒤 저장합니다.
     */
    var unitPrice by BillingPricingEvidence.unitPrice
    /**
     * 사용량 계량·청구의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    var effectiveAt by BillingPricingEvidence.effectiveAt
}

/**
 * BillingPriceEvidenceInboxEventEntity은 사용량 과금 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이
 * 타입의
 * 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
class BillingPriceEvidenceInboxEventEntity(id: EntityID<UUID>): UUIDEntity(id) {
    companion object: UUIDEntityClass<BillingPriceEvidenceInboxEventEntity>(BillingPriceEvidenceInboxEvents)
    /**
     * 업무 추적에 사용하는 불변 식별자입니다. 재시도와 비동기 전달에서도 같은 값을 유지합니다.
     */

    var eventId by BillingPriceEvidenceInboxEvents.eventId
    /**
     * 사용량 계량·청구 요청이 속한 테넌트 식별자입니다. 저장·이벤트·멱등성 범위를 이 값으로 분리합니다.
     */
    var tenantId by BillingPriceEvidenceInboxEvents.tenantId
    /**
     * 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
     */
    var payloadDigest by BillingPriceEvidenceInboxEvents.payloadDigest
    /**
     * 사용량 계량·청구의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    var createdAt by BillingPriceEvidenceInboxEvents.createdAt
}

/**
 * BillingInboxEventEntity은 사용량 과금 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
class BillingInboxEventEntity(id: EntityID<UUID>): UUIDEntity(id) {
    companion object: UUIDEntityClass<BillingInboxEventEntity>(BillingInboxEvents)
    /**
     * 업무 추적에 사용하는 불변 식별자입니다. 재시도와 비동기 전달에서도 같은 값을 유지합니다.
     */

    var eventId by BillingInboxEvents.eventId
    /**
     * 사용량 계량·청구 요청이 속한 테넌트 식별자입니다. 저장·이벤트·멱등성 범위를 이 값으로 분리합니다.
     */
    var tenantId by BillingInboxEvents.tenantId
    /**
     * `aggregateId`은 사용량 과금·정산에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    var aggregateId by BillingInboxEvents.aggregateId
    /**
     * 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값이 다르면 오래된 명령을 거부합니다.
     */
    var aggregateVersion by BillingInboxEvents.aggregateVersion
    /**
     * 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
     */
    var payloadDigest by BillingInboxEvents.payloadDigest
    /**
     * 사용량 계량·청구의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
     */
    var status by BillingInboxEvents.status
    /**
     * 사용량 계량·청구의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    var createdAt by BillingInboxEvents.createdAt
}

/**
 * BillingChargeEntity은 사용량 과금 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
class BillingChargeEntity(id: EntityID<UUID>): UUIDEntity(id) {
    companion object: UUIDEntityClass<BillingChargeEntity>(BillingCharges)
    /**
     * `sourceEventId`은 사용량 과금·정산에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */

    var sourceEventId by BillingCharges.sourceEventId
    /**
     * 사용량 계량·청구 요청이 속한 테넌트 식별자입니다. 저장·이벤트·멱등성 범위를 이 값으로 분리합니다.
     */
    var tenantId by BillingCharges.tenantId
    /**
     * `aggregateId`은 사용량 과금·정산에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    var aggregateId by BillingCharges.aggregateId
    /**
     * 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값이 다르면 오래된 명령을 거부합니다.
     */
    var aggregateVersion by BillingCharges.aggregateVersion
    /**
     * 청구·정산 금액 또는 통화입니다. 통화 일치와 반올림 규칙을 적용한 뒤 저장합니다.
     */
    var currency by BillingCharges.currency
    /**
     * 청구·정산 금액 또는 통화입니다. 통화 일치와 반올림 규칙을 적용한 뒤 저장합니다.
     */
    var unitPrice by BillingCharges.unitPrice
    /**
     * `quantity`은 사용량 과금·정산에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    var quantity by BillingCharges.quantity
    /**
     * 청구·정산 금액 또는 통화입니다. 통화 일치와 반올림 규칙을 적용한 뒤 저장합니다.
     */
    var amount by BillingCharges.amount
    /**
     * 사용량 계량·청구의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    var createdAt by BillingCharges.createdAt
}

@Suppress("AbstractClassCanBeConcreteClass")
/**
 * BillingExposedJdbcRepository은 사용량 과금 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에
 * 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
abstract class BillingExposedJdbcRepository<E: Entity<ID>, ID: Any>(
    domainClass: Class<E>,
): ExposedJdbcRepository<E, ID> by SimpleExposedJdbcRepository(ExposedEntityInformationImpl(domainClass))

@Suppress("AbstractClassCanBeConcreteClass")
/**
 * AppendOnlyBillingExposedJdbcRepository은 사용량 과금 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을
 * 이
 * 타입의 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
abstract class AppendOnlyBillingExposedJdbcRepository<E: Entity<ID>, ID: Any>(
    domainClass: KClass<E>,
): BillingExposedJdbcRepository<E, ID>(domainClass.java) {
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
 * BillingOutboxRepository은 사용량 과금 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
class BillingOutboxRepository:
    AppendOnlyBillingExposedJdbcRepository<BillingOutboxEventEntity, UUID>(BillingOutboxEventEntity::class)

@Repository
/**
 * BillingChargeRepository은 사용량 과금 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
class BillingChargeRepository:
    AppendOnlyBillingExposedJdbcRepository<BillingChargeEntity, UUID>(BillingChargeEntity::class)

@Repository
/**
 * ExposedBillingAdjustmentJournal은 사용량 과금 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의
 * 규칙에
 * 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
class ExposedBillingAdjustmentJournal: BillingAdjustmentJournal {
    /**
     * post은 사용량 계량·청구 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param command 사용량 계량·청구 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     */
    override fun post(command: BillingAdjustmentCommand): BillingAdjustmentOutcome {
        BillingOutboxEventEntity
            .find { BillingOutboxEvents.eventId eq command.adjustmentEventId }
            .firstOrNull()
            ?.let { return BillingAdjustmentOutcome.DUPLICATE }
        /**
         * 사용량 계량·청구 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */

        val original = BillingOutboxEventEntity
            .find {
                (BillingOutboxEvents.eventId eq command.correctionOf) and
                        (BillingOutboxEvents.tenantId eq command.tenantId) and
                        (BillingOutboxEvents.eventType eq "ChargeRated")
            }
            .firstOrNull()
            .requireNotNull { "original_charge_event_not_found:${command.correctionOf}" }
        /**
         * 사용량 계량·청구의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
         */
        
        val now = Instant.now()
        /**
         * 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값이 다르면 오래된 명령을 거부합니다.
         */
        val nextVersion = BillingOutboxEventEntity
            .find {
                (BillingOutboxEvents.tenantId eq command.tenantId) and
                        (BillingOutboxEvents.aggregateId eq original.aggregateId)
            }
            .count() + 1L
        /**
         * 사용량 계량·청구 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */

        val envelope = BillingIntegrationEnvelope.create(
            eventId = command.adjustmentEventId,
            eventType = "AdjustmentPosted",
            schemaVersion = 1,
            tenantId = command.tenantId,
            aggregateId = original.aggregateId,
            aggregateVersion = nextVersion,
            payload = """{"correctionOf":"${command.correctionOf}",""" +
                    """"currency":"${command.currency}","amount":"${command.amount}"}""",
            occurredAt = now,
            recordedAt = now,
        )
        BillingOutboxEventEntity.new {
            eventId = command.adjustmentEventId
            tenantId = command.tenantId
            eventType = envelope.eventType
            aggregateType = envelope.aggregateType
            aggregateId = original.aggregateId
            aggregateVersion = nextVersion
            partitionKey = envelope.partitionKey()
            payload = envelope.wirePayload()
            payloadDigest = envelope.wirePayloadDigest()
            status = BillingOutboxStatus.PENDING.name
            attempt = 0
            nextAttemptAt = null
            claimOwner = null
            claimUntil = null
            createdAt = now
            updatedAt = now
        }
        return BillingAdjustmentOutcome.APPLIED
    }
}

@Repository
/**
 * BillingPricingEvidenceRepository은 사용량 과금 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의
 * 규칙에
 * 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
class BillingPricingEvidenceRepository:
    AppendOnlyBillingExposedJdbcRepository<BillingPricingEvidenceEntity, UUID>(BillingPricingEvidenceEntity::class) {
    /**
     * append은 사용량 계량·청구 변경을 outbox·이벤트 계약으로 전달하되 commit 순서, partition key, 중복 소비 방지와 재전송 결과를 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param meterCode 업무 처리에 필요한 meterCode 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun append(meterCode: String) {
        append(
            BillingPriceEvidence(
                tenantId = "tenant-a",
                meterCode = meterCode,
                currency = "USD",
                unitPrice = java.math.BigDecimal.ONE,
                effectiveAt = Instant.EPOCH,
            ),
        )
    }

    /**
     * append은 사용량 계량·청구 변경을 outbox·이벤트 계약으로 전달하되 commit 순서, partition key, 중복 소비 방지와 재전송 결과를 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param evidence 업무 처리에 필요한 evidence 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun append(evidence: BillingPriceEvidence) {
        BillingPricingEvidenceEntity.new {
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
 * BillingPriceEvidenceInboxRepository은 사용량 과금 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이
 * 타입의
 * 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
class BillingPriceEvidenceInboxRepository:
    AppendOnlyBillingExposedJdbcRepository<BillingPriceEvidenceInboxEventEntity, UUID>(
        BillingPriceEvidenceInboxEventEntity::class
    ) {
    /**
     * record은 사용량 계량·청구 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param event 사용량 계량·청구 상태 변경을 나타내는 이벤트 또는 저장 레코드입니다. 순서·revision·중복 여부를 보존해야 합니다.
     */
    fun record(event: BillingPriceEvidenceEvent): BillingPriceEvidenceOutcome {
        val evidence = event.evidence
        val inserted = BillingPriceEvidenceInboxEvents.insertIgnore {
            it[eventId] = event.eventId
            it[tenantId] = evidence.tenantId
            it[payloadDigest] = event.payloadDigest
            it[createdAt] = evidence.effectiveAt
        }.insertedCount == 1
        if (!inserted) {
            val existing = BillingPriceEvidenceInboxEvents
                .selectAll()
                .where {
                    (BillingPriceEvidenceInboxEvents.tenantId eq evidence.tenantId) and
                            (BillingPriceEvidenceInboxEvents.eventId eq event.eventId)
                }
                .singleOrNull()

            return when (existing?.get(BillingPriceEvidenceInboxEvents.payloadDigest)) {
                event.payloadDigest -> BillingPriceEvidenceOutcome.DUPLICATE
                else -> BillingPriceEvidenceOutcome.QUARANTINED
            }
        }
        BillingPricingEvidenceEntity.new {
            tenantId = evidence.tenantId
            meterCode = evidence.meterCode
            currency = evidence.currency
            unitPrice = evidence.unitPrice
            effectiveAt = evidence.effectiveAt
        }
        return BillingPriceEvidenceOutcome.APPLIED
    }
}

@Repository
/**
 * BillingInboxRepository은 사용량 과금 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
class BillingInboxRepository:
    AppendOnlyBillingExposedJdbcRepository<BillingInboxEventEntity, UUID>(BillingInboxEventEntity::class)

@Repository
/**
 * ExposedBillingInboxJournal은 사용량 과금 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에
 * 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
class ExposedBillingInboxJournal: BillingInboxJournal {

    companion object: KLogging()
    /**
     * 사용량 계량·청구 경계의 전달 데이터입니다. 스키마·크기·무결성을 검증한 뒤 저장하거나 발행합니다.
     */

    override val appliedEventIds: Set<UUID>
        get() = BillingInboxEventEntity
            .find { BillingInboxEvents.status eq "APPLIED" }
            .map { it.eventId }
            .toSet()

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
    override fun priceEvidence(tenantId: String, meterCode: String, currency: String): BillingPriceEvidence? =
        BillingPricingEvidenceEntity
            .find {
                (BillingPricingEvidence.tenantId eq tenantId) and
                        (BillingPricingEvidence.meterCode eq meterCode) and
                        (BillingPricingEvidence.currency eq currency)
            }
            .firstOrNull()
            ?.let { evidence ->
                BillingPriceEvidence(
                    tenantId = evidence.tenantId,
                    meterCode = evidence.meterCode,
                    currency = evidence.currency,
                    unitPrice = evidence.unitPrice,
                    effectiveAt = evidence.effectiveAt,
                )
            }

    /**
     * digestFor은 사용량 계량·청구 요청의 인증·권한·무결성을 확인하고, 실패 시 민감한 내부 상태를 노출하지 않은 채 업무 처리를 차단합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param eventId 처리할 사용량 계량·청구 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    override fun digestFor(eventId: UUID): String? =
        BillingInboxEventEntity
            .find { BillingInboxEvents.eventId eq eventId }
            .firstOrNull()?.payloadDigest

    /**
     * expectedVersion은 사용량 계량·청구 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께
     * 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 사용량 계량·청구 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param aggregateId 처리할 사용량 계량·청구 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    override fun expectedVersion(tenantId: String, aggregateId: String): Long =
        BillingInboxEventEntity.find {
            (BillingInboxEvents.tenantId eq tenantId) and
                    (BillingInboxEvents.aggregateId eq aggregateId) and
                    (BillingInboxEvents.status eq "APPLIED")
        }.count() + 1L

    /**
     * apply은 사용량 계량·청구 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param event 사용량 계량·청구 상태 변경을 나타내는 이벤트 또는 저장 레코드입니다. 순서·revision·중복 여부를 보존해야 합니다.
     */
    override fun apply(event: BillingInboxEvent) {
        val now = Instant.now()
        val price = priceEvidence(event.tenantId, event.meterCode, event.currency)
            .checkNotNull { "pricing evidence must exist before rating" }
        val amount = price.unitPrice.multiply(event.quantity)

        BillingInboxEventEntity.new {
            eventId = event.eventId
            tenantId = event.tenantId
            aggregateId = event.aggregateId
            aggregateVersion = event.aggregateVersion
            payloadDigest = event.payloadDigest
            status = "APPLIED"
            createdAt = now
        }
        BillingChargeEntity.new {
            sourceEventId = event.eventId
            tenantId = event.tenantId
            aggregateId = event.aggregateId
            aggregateVersion = event.aggregateVersion
            currency = event.currency
            unitPrice = price.unitPrice
            quantity = event.quantity
            this.amount = amount
            createdAt = now
        }
        val outboxEventId = Uuid.V7.nextId()

        val envelope = BillingIntegrationEnvelope.create(
            eventId = outboxEventId,
            eventType = "ChargeRated",
            schemaVersion = 1,
            tenantId = event.tenantId,
            aggregateId = event.aggregateId,
            aggregateVersion = event.aggregateVersion,
            payload = """{"sourceEventId":"${event.eventId}",""" +
                    """"currency":"${event.currency}",""" +
                    """"amount":"$amount"}""",
            occurredAt = now,
            recordedAt = now,
        )
        BillingOutboxEventEntity.new {
            eventId = outboxEventId
            tenantId = event.tenantId
            eventType = envelope.eventType
            aggregateType = envelope.aggregateType
            aggregateId = event.aggregateId
            aggregateVersion = event.aggregateVersion
            partitionKey = envelope.partitionKey()
            payload = envelope.wirePayload()
            payloadDigest = envelope.wirePayloadDigest()
            status = "PENDING"
            attempt = 0
            nextAttemptAt = null
            claimOwner = null
            claimUntil = null
            createdAt = now
            updatedAt = now
        }
    }
}

@Repository
/**
 * ExposedBillingOutboxJournal은 사용량 과금 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에
 * 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
class ExposedBillingOutboxJournal: BillingOutboxJournal {

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
    override fun claim(owner: String, now: Instant, limit: Int): List<BillingOutboxLease> =
        BillingOutboxEvents
            .selectAll()
            .where { claimableAt(now) }
            .orderBy(BillingOutboxEvents.createdAt to SortOrder.ASC, BillingOutboxEvents.id to SortOrder.ASC)
            .limit(limit)
            .mapNotNull { row ->
                /**
                 * `eventId`은 사용량 과금·정산에서 업무 대상의 불변 식별자입니다.
                 *
                 * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
                 */
                val eventId = row[BillingOutboxEvents.eventId]
                /**
                 * `claimed`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
                 *
                 * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
                 */
                val claimed = BillingOutboxEvents.update({
                    (BillingOutboxEvents.eventId eq eventId) and claimableAt(now)
                }) {
                    it[status] = BillingOutboxStatus.CLAIMED.name
                    it[claimOwner] = owner
                    it[claimUntil] = now.plus(CLAIM_LEASE)
                    it[updatedAt] = now
                }
                if (claimed != 1) return@mapNotNull null

                BillingOutboxEvents
                    .selectAll()
                    .where { BillingOutboxEvents.eventId eq eventId }
                    .singleOrNull()
                    ?.let { claimedRow ->
                        BillingOutboxLease(
                            claimedRow[BillingOutboxEvents.eventId],
                            claimedRow[BillingOutboxEvents.partitionKey],
                            claimedRow[BillingOutboxEvents.payload],
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
        BillingOutboxEvents.update({ claimedBy(owner, eventId, now) }) {
            it[status] = BillingOutboxStatus.PUBLISHED.name
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
        BillingOutboxEvents
            .selectAll()
            .where { claimedBy(owner, eventId, now) }
            .singleOrNull()
            ?.let { row ->
                /**
                 * `attempts`은 사용량 과금·정산에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
                 *
                 * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
                 */
                val attempts = row[BillingOutboxEvents.attempt] + 1
                BillingOutboxEvents.update({ claimedBy(owner, eventId, now) }) {
                    it[attempt] = attempts
                    it[status] = if (attempts >= MAX_ATTEMPTS) {
                        BillingOutboxStatus.QUARANTINED.name
                    } else {
                        BillingOutboxStatus.RETRY_WAIT.name
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
        (BillingOutboxEvents.status eq BillingOutboxStatus.PENDING.name) or
                ((BillingOutboxEvents.status eq BillingOutboxStatus.RETRY_WAIT.name) and
                        (BillingOutboxEvents.nextAttemptAt lessEq now)) or
                ((BillingOutboxEvents.status eq BillingOutboxStatus.CLAIMED.name) and
                        (BillingOutboxEvents.claimUntil lessEq now))

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
        (BillingOutboxEvents.eventId eq eventId) and
                (BillingOutboxEvents.status eq BillingOutboxStatus.CLAIMED.name) and
                (BillingOutboxEvents.claimOwner eq owner) and
                (BillingOutboxEvents.claimUntil greater now)

}
