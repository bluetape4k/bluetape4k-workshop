@file:Suppress("MagicNumber") // SQL column sizes are schema declarations.

package io.bluetape4k.workshop.commerce.usagebilling.meter.persistence

import io.bluetape4k.javatimes.seconds
import io.bluetape4k.workshop.commerce.usagebilling.meter.domain.MeterCommandJournal
import io.bluetape4k.workshop.commerce.usagebilling.meter.domain.MeterCommandReceipt
import io.bluetape4k.workshop.commerce.usagebilling.meter.domain.MeterOutboxRecord
import io.bluetape4k.workshop.commerce.usagebilling.meter.domain.MeterPriceVersion
import io.bluetape4k.workshop.commerce.usagebilling.meter.messaging.MeterOutboxJournal
import io.bluetape4k.workshop.commerce.usagebilling.meter.messaging.MeterOutboxLease
import io.bluetape4k.workshop.commerce.usagebilling.meter.messaging.MeterOutboxStatus
import io.bluetape4k.spring.data.exposed.jdbc.repository.ExposedJdbcRepository
import io.bluetape4k.spring.data.exposed.jdbc.repository.support.ExposedEntityInformationImpl
import io.bluetape4k.spring.data.exposed.jdbc.repository.support.SimpleExposedJdbcRepository
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.dao.Entity
import org.jetbrains.exposed.v1.dao.java.UUIDEntity
import org.jetbrains.exposed.v1.dao.java.UUIDEntityClass
import org.jetbrains.exposed.v1.javatime.timestamp
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * MeterOutboxEvents은 사용량 과금 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
object MeterOutboxEvents : UUIDTable("meter_outbox_event", "outbox_event_id") {
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
 * MeterPriceVersions은 사용량 과금 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
object MeterPriceVersions : UUIDTable("meter_price_version", "price_version_id") {
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
    /**
     * 사용량 계량·청구의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    val createdAt = timestamp("created_at")

    init {
        uniqueIndex(tenantId, meterCode, currency, effectiveAt)
    }
}

/**
 * MeterCommandReceipts은 사용량 과금 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
object MeterCommandReceipts : UUIDTable("meter_command_receipt", "receipt_id") {
    /**
     * 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
     */
    val idempotencyKey = varchar("idempotency_key", 128)
    /**
     * 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
     */
    val fingerprint = varchar("fingerprint", 64)
    /**
     * `priceVersionId`은 사용량 과금·정산에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val priceVersionId = javaUUID("price_version_id")
    /**
     * 업무 추적에 사용하는 불변 식별자입니다. 재시도와 비동기 전달에서도 같은 값을 유지합니다.
     */
    val eventId = javaUUID("event_id")
    /**
     * 사용량 계량·청구의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    val createdAt = timestamp("created_at")

    init {
        uniqueIndex(idempotencyKey)
    }
}

/**
 * MeterOutboxEventEntity은 사용량 과금 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
class MeterOutboxEventEntity(id: EntityID<UUID>) : UUIDEntity(id) {
    companion object : UUIDEntityClass<MeterOutboxEventEntity>(MeterOutboxEvents)
    /**
     * 업무 추적에 사용하는 불변 식별자입니다. 재시도와 비동기 전달에서도 같은 값을 유지합니다.
     */

    var eventId by MeterOutboxEvents.eventId
    /**
     * 사용량 계량·청구 요청이 속한 테넌트 식별자입니다. 저장·이벤트·멱등성 범위를 이 값으로 분리합니다.
     */
    var tenantId by MeterOutboxEvents.tenantId
    /**
     * 사용량 계량·청구의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
     */
    var eventType by MeterOutboxEvents.eventType
    /**
     * 사용량 계량·청구의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
     */
    var aggregateType by MeterOutboxEvents.aggregateType
    /**
     * `aggregateId`은 사용량 과금·정산에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    var aggregateId by MeterOutboxEvents.aggregateId
    /**
     * 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값이 다르면 오래된 명령을 거부합니다.
     */
    var aggregateVersion by MeterOutboxEvents.aggregateVersion
    /**
     * 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
     */
    var partitionKey by MeterOutboxEvents.partitionKey
    /**
     * 사용량 계량·청구 경계의 전달 데이터입니다. 스키마·크기·무결성을 검증한 뒤 저장하거나 발행합니다.
     */
    var payload by MeterOutboxEvents.payload
    /**
     * 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
     */
    var payloadDigest by MeterOutboxEvents.payloadDigest
    /**
     * 사용량 계량·청구의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
     */
    var status by MeterOutboxEvents.status
    /**
     * `attempt`은 사용량 과금·정산에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    var attempt by MeterOutboxEvents.attempt
    /**
     * 사용량 계량·청구의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    var nextAttemptAt by MeterOutboxEvents.nextAttemptAt
    /**
     * 사용량 계량·청구 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    var claimOwner by MeterOutboxEvents.claimOwner
    /**
     * 사용량 계량·청구의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    var claimUntil by MeterOutboxEvents.claimUntil
    /**
     * 사용량 계량·청구의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    var createdAt by MeterOutboxEvents.createdAt
    /**
     * 사용량 계량·청구의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    var updatedAt by MeterOutboxEvents.updatedAt
}

/**
 * MeterPriceVersionEntity은 사용량 과금 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
class MeterPriceVersionEntity(id: EntityID<UUID>) : UUIDEntity(id) {
    companion object : UUIDEntityClass<MeterPriceVersionEntity>(MeterPriceVersions)
    /**
     * 사용량 계량·청구 요청이 속한 테넌트 식별자입니다. 저장·이벤트·멱등성 범위를 이 값으로 분리합니다.
     */

    var tenantId by MeterPriceVersions.tenantId
    /**
     * 사용량 계량·청구의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
     */
    var meterCode by MeterPriceVersions.meterCode
    /**
     * 청구·정산 금액 또는 통화입니다. 통화 일치와 반올림 규칙을 적용한 뒤 저장합니다.
     */
    var currency by MeterPriceVersions.currency
    /**
     * 청구·정산 금액 또는 통화입니다. 통화 일치와 반올림 규칙을 적용한 뒤 저장합니다.
     */
    var unitPrice by MeterPriceVersions.unitPrice
    /**
     * 사용량 계량·청구의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    var effectiveAt by MeterPriceVersions.effectiveAt
    /**
     * 사용량 계량·청구의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    var createdAt by MeterPriceVersions.createdAt
}

/**
 * MeterCommandReceiptEntity은 사용량 과금 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
class MeterCommandReceiptEntity(id: EntityID<UUID>) : UUIDEntity(id) {
    companion object : UUIDEntityClass<MeterCommandReceiptEntity>(MeterCommandReceipts)
    /**
     * 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
     */

    var idempotencyKey by MeterCommandReceipts.idempotencyKey
    /**
     * 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
     */
    var fingerprint by MeterCommandReceipts.fingerprint
    /**
     * `priceVersionId`은 사용량 과금·정산에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    var priceVersionId by MeterCommandReceipts.priceVersionId
    /**
     * 업무 추적에 사용하는 불변 식별자입니다. 재시도와 비동기 전달에서도 같은 값을 유지합니다.
     */
    var eventId by MeterCommandReceipts.eventId
    /**
     * 사용량 계량·청구의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    var createdAt by MeterCommandReceipts.createdAt
}

@Suppress("AbstractClassCanBeConcreteClass")
/**
 * MeterExposedJdbcRepository은 사용량 과금 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에
 * 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
abstract class MeterExposedJdbcRepository<E : Entity<ID>, ID : Any>(
    domainClass: Class<E>,
) : ExposedJdbcRepository<E, ID> by SimpleExposedJdbcRepository(ExposedEntityInformationImpl(domainClass))

@Suppress("AbstractClassCanBeConcreteClass")
/**
 * AppendOnlyMeterExposedJdbcRepository은 사용량 과금 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이
 * 타입의
 * 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
abstract class AppendOnlyMeterExposedJdbcRepository<E : Entity<ID>, ID : Any>(
    domainClass: Class<E>,
) : MeterExposedJdbcRepository<E, ID>(domainClass) {
    /**
     * save은 사용량 계량·청구 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param entity 업무 처리에 필요한 entity 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    final override fun <S : E> save(entity: S): S = immutableMutation()
    /**
     * saveAll은 사용량 계량·청구 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param entities 업무 처리에 필요한 entities 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    final override fun <S : E> saveAll(entities: Iterable<S>): List<S> = immutableMutation()
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
 * MeterOutboxRepository은 사용량 과금 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
class MeterOutboxRepository :
    AppendOnlyMeterExposedJdbcRepository<MeterOutboxEventEntity, UUID>(MeterOutboxEventEntity::class.java)

@Repository
/**
 * MeterPriceVersionRepository은 사용량 과금 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에
 * 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
class MeterPriceVersionRepository :
    AppendOnlyMeterExposedJdbcRepository<MeterPriceVersionEntity, UUID>(MeterPriceVersionEntity::class.java)

@Repository
/**
 * MeterCommandReceiptRepository은 사용량 과금 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의
 * 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
class MeterCommandReceiptRepository :
    AppendOnlyMeterExposedJdbcRepository<MeterCommandReceiptEntity, UUID>(MeterCommandReceiptEntity::class.java)

@Repository
/**
 * ExposedMeterCommandJournal은 사용량 과금 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에
 * 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
class ExposedMeterCommandJournal : MeterCommandJournal {
    /**
     * findReceipt은 사용량 계량·청구의 commit된 source-of-truth를 읽어 호출자에게 반환하며, 알림이나 캐시만으로 현재 상태를 추정하지 않습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param idempotencyKey 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     */
    override fun findReceipt(idempotencyKey: String): MeterCommandReceipt? =
        MeterCommandReceiptEntity.find { MeterCommandReceipts.idempotencyKey eq idempotencyKey }
            .firstOrNull()
            ?.let { entity ->
                MeterCommandReceipt(
                    idempotencyKey = entity.idempotencyKey,
                    fingerprint = entity.fingerprint,
                    result = io.bluetape4k.workshop.commerce.usagebilling.meter.domain.MeterActivationResult(
                        priceVersionId = entity.priceVersionId,
                        eventId = entity.eventId,
                        replayed = false,
                    ),
                )
            }

    /**
     * append은 사용량 계량·청구 변경을 outbox·이벤트 계약으로 전달하되 commit 순서, partition key, 중복 소비 방지와 재전송 결과를 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param receipt 업무 처리에 필요한 receipt 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param priceVersion 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     * @param outboxRecord 사용량 계량·청구 상태 변경을 나타내는 이벤트 또는 저장 레코드입니다. 순서·revision·중복 여부를 보존해야 합니다.
     */
    override fun append(
        receipt: MeterCommandReceipt,
        priceVersion: MeterPriceVersion,
        outboxRecord: MeterOutboxRecord,
    ) {
        MeterPriceVersionEntity.new(priceVersion.priceVersionId) {
            tenantId = priceVersion.tenantId
            meterCode = priceVersion.meterCode
            currency = priceVersion.currency
            unitPrice = priceVersion.unitPrice
            effectiveAt = priceVersion.effectiveAt
            createdAt = priceVersion.createdAt
        }
        MeterOutboxEventEntity.new {
            eventId = outboxRecord.eventId
            tenantId = priceVersion.tenantId
            eventType = outboxRecord.eventType
            aggregateType = "Meter"
            aggregateId = priceVersion.meterCode
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
        MeterCommandReceiptEntity.new {
            idempotencyKey = receipt.idempotencyKey
            fingerprint = receipt.fingerprint
            priceVersionId = receipt.result.priceVersionId
            eventId = receipt.result.eventId
            createdAt = outboxRecord.createdAt
        }
    }
}

@Repository
/**
 * ExposedMeterOutboxJournal은 사용량 과금 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
class ExposedMeterOutboxJournal : MeterOutboxJournal {
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
    override fun claim(owner: String, now: Instant, limit: Int): List<MeterOutboxLease> =
        MeterOutboxEvents.selectAll()
            .where { claimableAt(now) }
            .orderBy(MeterOutboxEvents.createdAt to SortOrder.ASC, MeterOutboxEvents.id to SortOrder.ASC)
            .take(limit)
            .mapNotNull { row ->
                /**
                 * `eventId`은 사용량 과금·정산에서 업무 대상의 불변 식별자입니다.
                 *
                 * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
                 */
                val eventId = row[MeterOutboxEvents.eventId]
                /**
                 * `claimed`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
                 *
                 * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
                 */
                val claimed = MeterOutboxEvents.update({
                    (MeterOutboxEvents.eventId eq eventId) and claimableAt(now)
                }) {
                    it[status] = MeterOutboxStatus.CLAIMED.name
                    it[claimOwner] = owner
                    it[claimUntil] = now.plus(CLAIM_LEASE)
                    it[updatedAt] = now
                }
                if (claimed != 1) return@mapNotNull null
                MeterOutboxEvents.selectAll()
                    .where { MeterOutboxEvents.eventId eq eventId }
                    .singleOrNull()
                    ?.let { claimedRow ->
                        MeterOutboxLease(
                            eventId = claimedRow[MeterOutboxEvents.eventId],
                            partitionKey = claimedRow[MeterOutboxEvents.partitionKey],
                            payload = claimedRow[MeterOutboxEvents.payload],
                            status = MeterOutboxStatus.CLAIMED,
                            claimOwner = owner,
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
        MeterOutboxEvents.update({ claimedBy(owner, eventId, now) }) {
            it[status] = MeterOutboxStatus.PUBLISHED.name
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
        MeterOutboxEvents.selectAll()
            .where { claimedBy(owner, eventId, now) }
            .singleOrNull()
            ?.let { row ->
                /**
                 * `attempts`은 사용량 과금·정산에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
                 *
                 * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
                 */
                val attempts = row[MeterOutboxEvents.attempt] + 1
                MeterOutboxEvents.update({ claimedBy(owner, eventId, now) }) {
                    it[attempt] = attempts
                    it[status] = if (attempts >= MAX_ATTEMPTS) {
                        MeterOutboxStatus.QUARANTINED.name
                    } else {
                        MeterOutboxStatus.RETRY_WAIT.name
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
        (MeterOutboxEvents.status eq MeterOutboxStatus.PENDING.name) or
            ((MeterOutboxEvents.status eq MeterOutboxStatus.RETRY_WAIT.name) and
                (MeterOutboxEvents.nextAttemptAt lessEq now)) or
            ((MeterOutboxEvents.status eq MeterOutboxStatus.CLAIMED.name) and
                (MeterOutboxEvents.claimUntil lessEq now))

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
        (MeterOutboxEvents.eventId eq eventId) and
            (MeterOutboxEvents.status eq MeterOutboxStatus.CLAIMED.name) and
            (MeterOutboxEvents.claimOwner eq owner) and
            (MeterOutboxEvents.claimUntil greater now)

    private companion object {
        /**
         * `CLAIM_LEASE`은 사용량 과금·정산에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
         *
         * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
         */
        val CLAIM_LEASE: Duration = 30.seconds()
        /**
         * `RETRY_DELAY`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val RETRY_DELAY: Duration = 1.seconds()
        /**
         * `MAX_ATTEMPTS`은 사용량 과금·정산에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
         *
         * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
         */
        const val MAX_ATTEMPTS = 3
    }
}
