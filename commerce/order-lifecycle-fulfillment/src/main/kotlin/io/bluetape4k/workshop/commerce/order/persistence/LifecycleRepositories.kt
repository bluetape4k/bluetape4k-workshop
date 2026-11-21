package io.bluetape4k.workshop.commerce.order.persistence

import io.bluetape4k.exposed.jdbc.repository.LongAuditableJdbcRepository
import io.bluetape4k.exposed.jdbc.repository.LongJdbcRepository
import io.bluetape4k.exposed.jdbc.repository.UUIDAuditableJdbcRepository
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.order.domain.CancellationStatus
import io.bluetape4k.workshop.commerce.order.domain.FulfillmentStatus
import io.bluetape4k.workshop.commerce.order.domain.OrderStatus
import io.bluetape4k.workshop.commerce.order.domain.PaymentStatus
import io.bluetape4k.workshop.commerce.order.domain.RefundStatus
import io.bluetape4k.workshop.commerce.order.domain.ReservationStatus
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.minus
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.stereotype.Repository
import java.util.*

@Repository
/**
 * OrderRepository은 주문 이행 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal class OrderRepository: UUIDAuditableJdbcRepository<OrderRecord, OrderTable> {

    companion object: KLogging()
    /**
     * 주문·fulfillment 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */

    override val table = OrderTable

    /**
     * extractId은 주문 라이프사이클과 fulfillment 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사
     * 필드를 함께
     * 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param entity 업무 처리에 필요한 entity 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    override fun extractId(entity: OrderRecord) = entity.id

    /**
     * toEntity은 주문 라이프사이클과 fulfillment 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사
     * 필드를 함께 보존합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    override fun ResultRow.toEntity(): OrderRecord = OrderRecord(
        id = this[table.id].value,
        tenantId = this[table.tenantId],
        customerReference = this[table.customerReference],
        status = this[table.status],
        revision = this[table.revision],
        providerMode = this[table.providerMode],
        cancelReason = this[table.cancelReason],
        createdBy = this[table.createdBy],
        createdAt = this[table.createdAt],
        updatedBy = this[table.updatedBy],
        updatedAt = this[table.updatedAt]
    )

    /**
     * save은 주문 라이프사이클과 fulfillment 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께
     * 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param record 주문 라이프사이클과 fulfillment 상태 변경을 나타내는 이벤트 또는 저장 레코드입니다. 순서·revision·중복 여부를 보존해야
     * 합니다.
     */
    fun save(record: OrderRecord): OrderRecord {
        table.insert {
            it[id] = record.id
            it[tenantId] = record.tenantId
            it[customerReference] = record.customerReference
            it[status] = record.status
            it[revision] = record.revision
            it[providerMode] = record.providerMode
            it[cancelReason] = record.cancelReason
        }
        return findById(record.id)
    }

    /**
     * transition은 주문 라이프사이클과 fulfillment 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사
     * 필드를 함께
     * 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param id 처리할 주문 라이프사이클과 fulfillment 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param expectedRevision 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     * @param from 업무 처리에 필요한 from 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param to 업무 처리에 필요한 to 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param reason 업무 처리에 필요한 reason 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun transition(
        id: UUID,
        expectedRevision: Long,
        from: OrderStatus,
        to: OrderStatus,
        reason: String? = null,
    ): Boolean =
        auditedUpdateAll(
            { (table.id eq id) and (table.revision eq expectedRevision) and (table.status eq from) }
        ) {
            it[status] = to
            it[revision] = expectedRevision + 1
            it[cancelReason] = reason?.take(80)
        } == 1
}

@Repository
/**
 * PaymentAttemptRepository은 주문 이행 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal class PaymentAttemptRepository: UUIDAuditableJdbcRepository<PaymentAttemptRecord, PaymentAttemptTable> {

    companion object: KLogging()
    /**
     * 주문·fulfillment 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */

    override val table = PaymentAttemptTable

    /**
     * extractId은 주문 라이프사이클과 fulfillment 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사
     * 필드를 함께
     * 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param entity 업무 처리에 필요한 entity 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    override fun extractId(entity: PaymentAttemptRecord) = entity.id

    /**
     * toEntity은 주문 라이프사이클과 fulfillment 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사
     * 필드를 함께 보존합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    override fun ResultRow.toEntity(): PaymentAttemptRecord = PaymentAttemptRecord(
        id = this[table.id].value,
        orderId = this[table.orderId],
        status = this[table.status],
        revision = this[table.revision],
        providerReference = this[table.providerReference],
        createdBy = this[table.createdBy],
        createdAt = this[table.createdAt],
        updatedBy = this[table.updatedBy],
        updatedAt = this[table.updatedAt]
    )

    /**
     * save은 주문 라이프사이클과 fulfillment 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께
     * 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param record 주문 라이프사이클과 fulfillment 상태 변경을 나타내는 이벤트 또는 저장 레코드입니다. 순서·revision·중복 여부를 보존해야
     * 합니다.
     */
    fun save(record: PaymentAttemptRecord): PaymentAttemptRecord {
        table.insert {
            it[id] = record.id
            it[orderId] = record.orderId
            it[status] = record.status
            it[revision] = record.revision
            it[providerReference] = record.providerReference
        }
        return findById(record.id)
    }

    /**
     * findByOrderId은 주문 라이프사이클과 fulfillment의 commit된 source-of-truth를 읽어 호출자에게 반환하며, 알림이나 캐시만으로 현재
     * 상태를
     * 추정하지 않습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param orderId 처리할 주문 라이프사이클과 fulfillment 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    fun findByOrderId(orderId: UUID): PaymentAttemptRecord? =
        table
            .selectAll()
            .where { table.orderId eq orderId }
            .orderBy(table.createdAt, SortOrder.DESC)
            .limit(1)
            .firstOrNull()
            ?.let { with(this) { it.toEntity() } }

    /**
     * transition은 주문 라이프사이클과 fulfillment 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사
     * 필드를 함께
     * 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param id 처리할 주문 라이프사이클과 fulfillment 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param expectedRevision 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     * @param from 업무 처리에 필요한 from 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param to 업무 처리에 필요한 to 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param providerRef 업무 처리에 필요한 providerRef 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun transition(
        id: UUID,
        expectedRevision: Long,
        from: PaymentStatus,
        to: PaymentStatus,
        providerRef: String? = null,
    ): Boolean =
        auditedUpdateAll(
            { (table.id eq id) and (table.revision eq expectedRevision) and (table.status eq from) }
        ) {
            it[status] = to
            it[revision] = expectedRevision + 1
            if (providerRef != null) it[providerReference] = providerRef.take(160)
        } == 1
}

@Repository
/**
 * InventoryReservationRepository은 주문 이행 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의
 * 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal class InventoryReservationRepository:
    UUIDAuditableJdbcRepository<InventoryReservationRecord, InventoryReservationTable> {

    companion object: KLogging()
    /**
     * 주문·fulfillment 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */

    override val table = InventoryReservationTable

    /**
     * extractId은 주문 라이프사이클과 fulfillment 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사
     * 필드를 함께
     * 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param entity 업무 처리에 필요한 entity 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    override fun extractId(entity: InventoryReservationRecord) = entity.id

    /**
     * toEntity은 주문 라이프사이클과 fulfillment 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사
     * 필드를 함께 보존합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    override fun ResultRow.toEntity(): InventoryReservationRecord = InventoryReservationRecord(
        id = this[table.id].value,
        orderId = this[table.orderId],
        status = this[table.status],
        revision = this[table.revision],
        reasonCode = this[table.reasonCode],
        createdBy = this[table.createdBy],
        createdAt = this[table.createdAt],
        updatedBy = this[table.updatedBy],
        updatedAt = this[table.updatedAt]
    )

    /**
     * save은 주문 라이프사이클과 fulfillment 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께
     * 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param record 주문 라이프사이클과 fulfillment 상태 변경을 나타내는 이벤트 또는 저장 레코드입니다. 순서·revision·중복 여부를 보존해야
     * 합니다.
     */
    fun save(record: InventoryReservationRecord): InventoryReservationRecord {
        table.insert {
            it[id] = record.id
            it[orderId] = record.orderId
            it[status] = record.status
            it[revision] = record.revision
            it[reasonCode] = record.reasonCode
        }
        return findById(record.id)
    }

    /**
     * findByOrderId은 주문 라이프사이클과 fulfillment의 commit된 source-of-truth를 읽어 호출자에게 반환하며, 알림이나 캐시만으로 현재
     * 상태를
     * 추정하지 않습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param orderId 처리할 주문 라이프사이클과 fulfillment 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    fun findByOrderId(orderId: UUID): InventoryReservationRecord? =
        table
            .selectAll()
            .where { table.orderId eq orderId }
            .firstOrNull()
            ?.let { with(this) { it.toEntity() } }

    /**
     * transition은 주문 라이프사이클과 fulfillment 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사
     * 필드를 함께
     * 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param id 처리할 주문 라이프사이클과 fulfillment 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param expectedRevision 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     * @param from 업무 처리에 필요한 from 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param to 업무 처리에 필요한 to 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param reason 업무 처리에 필요한 reason 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun transition(
        id: UUID,
        expectedRevision: Long,
        from: ReservationStatus,
        to: ReservationStatus,
        reason: String? = null,
    ): Boolean =
        auditedUpdateAll(
            { (table.id eq id) and (table.revision eq expectedRevision) and (table.status eq from) }
        ) {
            it[status] = to
            it[revision] = expectedRevision + 1
            it[reasonCode] = reason?.take(80)
        } == 1
}

@Repository
/**
 * FulfillmentGroupRepository은 주문 이행 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal class FulfillmentGroupRepository: UUIDAuditableJdbcRepository<FulfillmentGroupRecord, FulfillmentGroupTable> {

    companion object: KLogging()
    /**
     * 주문·fulfillment 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */

    override val table = FulfillmentGroupTable

    /**
     * extractId은 주문 라이프사이클과 fulfillment 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사
     * 필드를 함께
     * 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param entity 업무 처리에 필요한 entity 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    override fun extractId(entity: FulfillmentGroupRecord) = entity.id

    /**
     * toEntity은 주문 라이프사이클과 fulfillment 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사
     * 필드를 함께 보존합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    override fun ResultRow.toEntity(): FulfillmentGroupRecord = FulfillmentGroupRecord(
        id = this[table.id].value,
        orderId = this[table.orderId],
        groupReference = this[table.groupReference],
        status = this[table.status],
        revision = this[table.revision],
        cancelReason = this[table.cancelReason],
        createdBy = this[table.createdBy],
        createdAt = this[table.createdAt],
        updatedBy = this[table.updatedBy],
        updatedAt = this[table.updatedAt]
    )

    /**
     * save은 주문 라이프사이클과 fulfillment 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께
     * 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param record 주문 라이프사이클과 fulfillment 상태 변경을 나타내는 이벤트 또는 저장 레코드입니다. 순서·revision·중복 여부를 보존해야
     * 합니다.
     */
    fun save(record: FulfillmentGroupRecord): FulfillmentGroupRecord {
        table.insert {
            it[id] = record.id
            it[orderId] = record.orderId
            it[groupReference] = record.groupReference
            it[status] = record.status
            it[revision] = record.revision
            it[cancelReason] = record.cancelReason
        }
        return findById(record.id)
    }

    /**
     * findByOrderId은 주문 라이프사이클과 fulfillment의 commit된 source-of-truth를 읽어 호출자에게 반환하며, 알림이나 캐시만으로 현재
     * 상태를
     * 추정하지 않습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param orderId 처리할 주문 라이프사이클과 fulfillment 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    fun findByOrderId(orderId: UUID): List<FulfillmentGroupRecord> =
        table
            .selectAll()
            .where { table.orderId eq orderId }
            .orderBy(table.groupReference)
            .map { with(this) { it.toEntity() } }

    /**
     * transition은 주문 라이프사이클과 fulfillment 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사
     * 필드를 함께
     * 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param id 처리할 주문 라이프사이클과 fulfillment 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param expectedRevision 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     * @param from 업무 처리에 필요한 from 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param to 업무 처리에 필요한 to 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param reason 업무 처리에 필요한 reason 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun transition(
        id: UUID,
        expectedRevision: Long,
        from: FulfillmentStatus,
        to: FulfillmentStatus,
        reason: String? = null,
    ): Boolean =
        auditedUpdateAll(
            { (table.id eq id) and (table.revision eq expectedRevision) and (table.status eq from) }
        ) {
            it[status] = to
            it[revision] = expectedRevision + 1
            it[cancelReason] = reason?.take(80)
        } == 1
}

@Repository
/**
 * CancellationCaseRepository은 주문 이행 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal class CancellationCaseRepository: UUIDAuditableJdbcRepository<CancellationCaseRecord, CancellationCaseTable> {
    /**
     * 주문·fulfillment 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    override val table = CancellationCaseTable

    /**
     * extractId은 주문 라이프사이클과 fulfillment 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사
     * 필드를 함께
     * 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param entity 업무 처리에 필요한 entity 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    override fun extractId(entity: CancellationCaseRecord) = entity.id

    /**
     * toEntity은 주문 라이프사이클과 fulfillment 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사
     * 필드를 함께 보존합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    override fun ResultRow.toEntity(): CancellationCaseRecord = CancellationCaseRecord(
        id = this[table.id].value,
        orderId = this[table.orderId],
        lineId = this[table.lineId],
        quantity = this[table.quantity],
        status = this[table.status],
        revision = this[table.revision],
        reasonCode = this[table.reasonCode],
        createdBy = this[table.createdBy],
        createdAt = this[table.createdAt],
        updatedBy = this[table.updatedBy],
        updatedAt = this[table.updatedAt]
    )

    /**
     * save은 주문 라이프사이클과 fulfillment 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께
     * 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param record 주문 라이프사이클과 fulfillment 상태 변경을 나타내는 이벤트 또는 저장 레코드입니다. 순서·revision·중복 여부를 보존해야
     * 합니다.
     */
    fun save(record: CancellationCaseRecord): CancellationCaseRecord {
        table.insert {
            it[id] = record.id
            it[orderId] = record.orderId
            it[lineId] = record.lineId
            it[quantity] = record.quantity
            it[status] = record.status
            it[revision] = record.revision
            it[reasonCode] = record.reasonCode.take(80)
        }
        return findById(record.id)
    }

    /**
     * findByOrderId은 주문 라이프사이클과 fulfillment의 commit된 source-of-truth를 읽어 호출자에게 반환하며, 알림이나 캐시만으로 현재
     * 상태를
     * 추정하지 않습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param orderId 처리할 주문 라이프사이클과 fulfillment 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    fun findByOrderId(orderId: UUID): List<CancellationCaseRecord> =
        table
            .selectAll()
            .where { table.orderId eq orderId }
            .orderBy(table.createdAt)
            .map { it.toEntity() }

    /**
     * transition은 주문 라이프사이클과 fulfillment 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사
     * 필드를 함께
     * 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param id 처리할 주문 라이프사이클과 fulfillment 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param expectedRevision 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     * @param from 업무 처리에 필요한 from 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param to 업무 처리에 필요한 to 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun transition(
        id: UUID,
        expectedRevision: Long,
        from: CancellationStatus,
        to: CancellationStatus,
    ): Boolean =
        auditedUpdateAll(
            { (table.id eq id) and (table.revision eq expectedRevision) and (table.status eq from) }
        ) {
            it[status] = to
            it[revision] = expectedRevision + 1
        } == 1
}

@Repository
/**
 * RefundCaseRepository은 주문 이행 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal class RefundCaseRepository: UUIDAuditableJdbcRepository<RefundCaseRecord, RefundCaseTable> {
    /**
     * 주문·fulfillment 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    override val table = RefundCaseTable

    /**
     * extractId은 주문 라이프사이클과 fulfillment 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사
     * 필드를 함께
     * 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param entity 업무 처리에 필요한 entity 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    override fun extractId(entity: RefundCaseRecord) = entity.id

    /**
     * toEntity은 주문 라이프사이클과 fulfillment 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사
     * 필드를 함께 보존합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    override fun ResultRow.toEntity(): RefundCaseRecord = RefundCaseRecord(
        id = this[table.id].value,
        orderId = this[table.orderId],
        status = this[table.status],
        revision = this[table.revision],
        reasonCode = this[table.reasonCode],
        createdBy = this[table.createdBy],
        createdAt = this[table.createdAt],
        updatedBy = this[table.updatedBy],
        updatedAt = this[table.updatedAt]
    )

    /**
     * save은 주문 라이프사이클과 fulfillment 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께
     * 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param record 주문 라이프사이클과 fulfillment 상태 변경을 나타내는 이벤트 또는 저장 레코드입니다. 순서·revision·중복 여부를 보존해야
     * 합니다.
     */
    fun save(record: RefundCaseRecord): RefundCaseRecord {
        table.insert {
            it[id] = record.id
            it[orderId] = record.orderId
            it[status] = record.status
            it[revision] = record.revision
            it[reasonCode] = record.reasonCode.take(80)
        }
        return findById(record.id)
    }

    /**
     * findByOrderId은 주문 라이프사이클과 fulfillment의 commit된 source-of-truth를 읽어 호출자에게 반환하며, 알림이나 캐시만으로 현재
     * 상태를
     * 추정하지 않습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param orderId 처리할 주문 라이프사이클과 fulfillment 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    fun findByOrderId(orderId: UUID): List<RefundCaseRecord> =
        table
            .selectAll()
            .where { table.orderId eq orderId }
            .orderBy(table.createdAt)
            .map { it.toEntity() }

    /**
     * transition은 주문 라이프사이클과 fulfillment 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사
     * 필드를 함께
     * 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param id 처리할 주문 라이프사이클과 fulfillment 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param expectedRevision 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     * @param from 업무 처리에 필요한 from 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param to 업무 처리에 필요한 to 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun transition(
        id: UUID,
        expectedRevision: Long,
        from: RefundStatus,
        to: RefundStatus,
    ): Boolean =
        auditedUpdateAll(
            { (table.id eq id) and (table.revision eq expectedRevision) and (table.status eq from) }
        ) {
            it[status] = to
            it[revision] = expectedRevision + 1
        } == 1
}

@Repository
/**
 * OrderLineRepository은 주문 이행 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal class OrderLineRepository: LongAuditableJdbcRepository<OrderLineRecord, OrderLineTable> {
    /**
     * 주문·fulfillment 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    override val table = OrderLineTable

    /**
     * extractId은 주문 라이프사이클과 fulfillment 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사
     * 필드를 함께
     * 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param entity 업무 처리에 필요한 entity 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    override fun extractId(entity: OrderLineRecord) = entity.id

    /**
     * toEntity은 주문 라이프사이클과 fulfillment 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사
     * 필드를 함께 보존합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    override fun ResultRow.toEntity(): OrderLineRecord = OrderLineRecord(
        id = this[table.id].value,
        lineId = this[table.lineId],
        orderId = this[table.orderId],
        sku = this[table.sku],
        quantity = this[table.quantity],
        unitPrice = this[table.unitPrice],
        cancelledQuantity = this[table.cancelledQuantity],
        createdBy = this[table.createdBy],
        createdAt = this[table.createdAt],
        updatedBy = this[table.updatedBy],
        updatedAt = this[table.updatedAt]
    )

    /**
     * save은 주문 라이프사이클과 fulfillment 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께
     * 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param record 주문 라이프사이클과 fulfillment 상태 변경을 나타내는 이벤트 또는 저장 레코드입니다. 순서·revision·중복 여부를 보존해야
     * 합니다.
     */
    fun save(record: OrderLineRecord): OrderLineRecord {
        val id = table.insertAndGetId {
            it[lineId] = record.lineId
            it[orderId] = record.orderId
            it[sku] = record.sku.take(120)
            it[quantity] = record.quantity
            it[unitPrice] = record.unitPrice
            it[cancelledQuantity] = record.cancelledQuantity
        }.value
        return findById(id)
    }

    /**
     * findByOrderId은 주문 라이프사이클과 fulfillment의 commit된 source-of-truth를 읽어 호출자에게 반환하며, 알림이나 캐시만으로 현재
     * 상태를
     * 추정하지 않습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param orderId 처리할 주문 라이프사이클과 fulfillment 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    fun findByOrderId(orderId: UUID): List<OrderLineRecord> =
        table
            .selectAll()
            .where { table.orderId eq orderId }
            .orderBy(table.id)
            .map { it.toEntity() }

    /**
     * findByLineId은 주문 라이프사이클과 fulfillment의 commit된 source-of-truth를 읽어 호출자에게 반환하며, 알림이나 캐시만으로 현재
     * 상태를 추정하지
     * 않습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param lineId 처리할 주문 라이프사이클과 fulfillment 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    fun findByLineId(lineId: UUID): OrderLineRecord? =
        table
            .selectAll()
            .where { table.lineId eq lineId }
            .firstOrNull()
            ?.toEntity()

    /**
     * cancel은 주문 라이프사이클과 fulfillment의 취소·보상·종료 전이를 허용된 terminal 규칙에 따라 적용하고, 이미 처리된 요청은 멱등하게 유지합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param lineId 처리할 주문 라이프사이클과 fulfillment 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param quantity 업무 처리에 필요한 quantity 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun cancel(
        lineId: UUID,
        quantity: Int,
    ): Boolean =
        auditedUpdateAll(
            { (table.lineId eq lineId) and (table.cancelledQuantity lessEq (table.quantity - quantity)) }
        ) {
            it[cancelledQuantity] = table.cancelledQuantity + quantity
        } == 1
}

@Repository
/**
 * FulfillmentLineRepository은 주문 이행 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal class FulfillmentLineRepository {

    companion object: KLogging()

    /**
     * save은 주문 라이프사이클과 fulfillment 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께
     * 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param record 주문 라이프사이클과 fulfillment 상태 변경을 나타내는 이벤트 또는 저장 레코드입니다. 순서·revision·중복 여부를 보존해야
     * 합니다.
     */
    fun save(record: FulfillmentLineRecord): Boolean =
        FulfillmentLineTable.insertIgnore {
            it[fulfillmentGroupId] = record.fulfillmentGroupId
            it[lineId] = record.lineId
            it[quantity] = record.quantity
        }.insertedCount == 1

    /**
     * findByOrderId은 주문 라이프사이클과 fulfillment의 commit된 source-of-truth를 읽어 호출자에게 반환하며, 알림이나 캐시만으로 현재
     * 상태를
     * 추정하지 않습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param orderId 처리할 주문 라이프사이클과 fulfillment 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    fun findByOrderId(orderId: UUID): List<FulfillmentLineRecord> {
        val groupIds = FulfillmentGroupTable
            .selectAll()
            .where { FulfillmentGroupTable.orderId eq orderId }
            .map { it[FulfillmentGroupTable.id].value }

        if (groupIds.isEmpty()) return emptyList()

        return FulfillmentLineTable
            .selectAll()
            .where { FulfillmentLineTable.fulfillmentGroupId inList groupIds }
            .orderBy(FulfillmentLineTable.fulfillmentGroupId, SortOrder.ASC)
            .map(::toRecord)
    }

    /**
     * findByLineId은 주문 라이프사이클과 fulfillment의 commit된 source-of-truth를 읽어 호출자에게 반환하며, 알림이나 캐시만으로 현재
     * 상태를 추정하지
     * 않습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param lineId 처리할 주문 라이프사이클과 fulfillment 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    fun findByLineId(lineId: UUID): List<FulfillmentLineRecord> =
        FulfillmentLineTable.selectAll()
            .where { FulfillmentLineTable.lineId eq lineId }
            .map(::toRecord)

    /**
     * cancel은 주문 라이프사이클과 fulfillment의 취소·보상·종료 전이를 허용된 terminal 규칙에 따라 적용하고, 이미 처리된 요청은 멱등하게 유지합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param lineId 처리할 주문 라이프사이클과 fulfillment 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param cancellableGroupIds 업무 처리에 필요한 cancellableGroupIds 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야
     * 합니다.
     * @param cancelledQuantity 업무 처리에 필요한 cancelledQuantity 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun cancel(
        lineId: UUID,
        cancellableGroupIds: Set<UUID>,
        cancelledQuantity: Int,
    ): Boolean {
        if (cancellableGroupIds.isEmpty()) return false
        val links = FulfillmentLineTable
            .selectAll()
            .where {
                (FulfillmentLineTable.lineId eq lineId) and
                        (FulfillmentLineTable.fulfillmentGroupId inList cancellableGroupIds)
            }
            .orderBy(FulfillmentLineTable.fulfillmentGroupId, SortOrder.ASC)
            .map(::toRecord)

        if (links.sumOf { it.quantity } < cancelledQuantity) return false

        var remaining = cancelledQuantity
        links.forEach { link ->
            if (remaining > 0) {
                val decrement = minOf(link.quantity, remaining)
                val updated = FulfillmentLineTable.update(
                    where = {
                        (FulfillmentLineTable.fulfillmentGroupId eq link.fulfillmentGroupId) and
                                (FulfillmentLineTable.lineId eq lineId) and
                                (FulfillmentLineTable.quantity eq link.quantity)
                    }
                ) {
                    it[quantity] = link.quantity - decrement
                }
                if (updated != 1) return false
                remaining -= decrement
            }
        }
        return remaining == 0
    }

    /**
     * toRecord은 주문 라이프사이클과 fulfillment 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록
     * 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param row 업무 처리에 필요한 row 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun toRecord(row: ResultRow): FulfillmentLineRecord =
        FulfillmentLineRecord(
            fulfillmentGroupId = row[FulfillmentLineTable.fulfillmentGroupId],
            lineId = row[FulfillmentLineTable.lineId],
            quantity = row[FulfillmentLineTable.quantity]
        )
}

@Repository
/**
 * LifecycleAuditRepository은 주문 이행 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal class LifecycleAuditRepository: LongJdbcRepository<LifecycleAuditRecord> {

    companion object: KLogging()
    /**
     * 주문·fulfillment 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */

    override val table = LifecycleAuditTable

    /**
     * extractId은 주문 라이프사이클과 fulfillment 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사
     * 필드를 함께
     * 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param entity 업무 처리에 필요한 entity 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    override fun extractId(entity: LifecycleAuditRecord) = entity.id

    /**
     * toEntity은 주문 라이프사이클과 fulfillment 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사
     * 필드를 함께 보존합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    override fun ResultRow.toEntity() = LifecycleAuditRecord(
        id = this[table.id].value,
        eventId = this[table.eventId],
        orderId = this[table.orderId],
        aggregateType = this[table.aggregateType],
        aggregateId = this[table.aggregateId],
        revision = this[table.revision],
        fromStatus = this[table.fromStatus],
        toStatus = this[table.toStatus],
        reasonCode = this[table.reasonCode],
        actorType = this[table.actorType],
        occurredAt = this[table.occurredAt]
    )

    /**
     * append은 주문 라이프사이클과 fulfillment 변경을 outbox·이벤트 계약으로 전달하되 commit 순서, partition key, 중복 소비 방지와
     * 재전송 결과를
     * 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param record 주문 라이프사이클과 fulfillment 상태 변경을 나타내는 이벤트 또는 저장 레코드입니다. 순서·revision·중복 여부를 보존해야
     * 합니다.
     */
    fun append(record: LifecycleAuditRecord): LifecycleAuditRecord {
        val id = table.insertAndGetId {
            it[eventId] = record.eventId
            it[orderId] = record.orderId
            it[aggregateType] = record.aggregateType
            it[aggregateId] = record.aggregateId
            it[revision] = record.revision
            it[fromStatus] = record.fromStatus
            it[toStatus] = record.toStatus
            it[reasonCode] = record.reasonCode?.take(80)
            it[actorType] = record.actorType.take(40)
        }.value

        return findById(id)
    }

    /**
     * findByOrderId은 주문 라이프사이클과 fulfillment의 commit된 source-of-truth를 읽어 호출자에게 반환하며, 알림이나 캐시만으로 현재
     * 상태를
     * 추정하지 않습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param orderId 처리할 주문 라이프사이클과 fulfillment 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param afterId 처리할 주문 라이프사이클과 fulfillment 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    fun findByOrderId(
        orderId: UUID,
        afterId: Long = 0,
    ): List<LifecycleAuditRecord> =
        table
            .selectAll()
            .where { table.orderId eq orderId }
            .andWhere { table.id greater afterId }
            .orderBy(table.id)
            .map { it.toEntity() }
}
