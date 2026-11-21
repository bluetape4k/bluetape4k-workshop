package io.bluetape4k.workshop.commerce.reservation.notification

import io.bluetape4k.exposed.core.auditable.Auditable
import io.bluetape4k.exposed.core.auditable.AuditableLongIdTable
import io.bluetape4k.exposed.core.auditable.UserContext
import io.bluetape4k.exposed.jdbc.repository.LongAuditableJdbcRepository
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.javatime.timestamp
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.springframework.stereotype.Repository
import java.io.Serializable
import java.time.Instant

/** enqueue deduplication을 위해 안정적인 delivery id로 keying되는 durable outbox row입니다. */
/** enqueue deduplication을 위해 안정적인 delivery id로 keying되는 durable outbox row입니다. */
internal object NotificationDeliveryTable: AuditableLongIdTable("reservation_notification_deliveries") {
    /**
     * `deliveryId`은 예약 제어면에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val deliveryId = varchar("delivery_id", 160).uniqueIndex()
    /**
     * 좌석 예약·대기열 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    val channel = enumerationByName<NotificationChannel>("channel", 24)
    /**
     * 좌석 예약·대기열의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
     */
    val templateCode = varchar("template_code", 80)
    /**
     * `aggregateId`은 예약 제어면에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val aggregateId = varchar("aggregate_id", 80)
    /**
     * 좌석 예약·대기열의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
     */
    val status = enumerationByName<NotificationDeliveryStatus>("status", 24)
    /**
     * `attemptCount`은 예약 제어면에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val attemptCount = integer("attempt_count").default(0)
    /**
     * 좌석 예약·대기열의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    val nextAttemptAt = timestamp("next_attempt_at").nullable()
    /**
     * 좌석 예약·대기열 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    val claimOwner = varchar("claim_owner", 80).nullable()
    /**
     * 좌석 예약·대기열의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    val claimUntil = timestamp("claim_until").nullable()
    /**
     * 좌석 예약·대기열의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
     */
    val failureCode = enumerationByName<NotificationFailureCode>("failure_code", 32).nullable()

    init {
        index(false, status, nextAttemptAt, id)
    }
}
/**
 * NotificationDeliveryRecord은 좌석 예약·대기열에서 전달되는 불변 데이터 모델입니다.
 * @property id 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property delivery 좌석 예약·대기열 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property createdBy 좌석 예약·대기열 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property createdAt 좌석 예약·대기열의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock 정책을
 * 유지합니다.
 * @property updatedBy 좌석 예약·대기열 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property updatedAt 좌석 예약·대기열의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock 정책을
 * 유지합니다.
 */


internal data class NotificationDeliveryRecord(
    /**
     * `id`은 예약 제어면에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val id: Long,
    /**
     * `delivery`은 예약 제어면에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val delivery: NotificationDelivery,
    /**
     * `createdBy`은 예약 제어면에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    override val createdBy: String = UserContext.DEFAULT_USERNAME,
    /**
     * `createdAt`은 예약 제어면에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    override val createdAt: Instant? = null,
    /**
     * `updatedBy`은 예약 제어면에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    override val updatedBy: String? = null,
    /**
     * `updatedAt`은 예약 제어면에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    override val updatedAt: Instant? = null,
): Auditable,
   Serializable {
    companion object {
        /**
         * `serialVersionUID`은 예약 제어면에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID = 1L
    }
}

/** provider effect가 발생하기 전에 caller의 PostgreSQL transaction에 notification intent를 저장합니다. */
/** provider effect가 발생하기 전에 caller의 PostgreSQL transaction에 notification intent를 저장합니다. */
@Repository
internal class NotificationDeliveryRepository
    : LongAuditableJdbcRepository<NotificationDeliveryRecord, NotificationDeliveryTable> {

    companion object: KLogging()
    /**
     * 좌석 예약·대기열 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */

    override val table: NotificationDeliveryTable = NotificationDeliveryTable

    /**
     * extractId은 예약 제어면 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param entity 업무 처리에 필요한 entity 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    override fun extractId(entity: NotificationDeliveryRecord): Long = entity.id

    /**
     * toEntity은 예약 제어면 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    override fun ResultRow.toEntity(): NotificationDeliveryRecord =
        NotificationDeliveryRecord(
            id = this[table.id].value,
            delivery = NotificationDelivery(
                deliveryId = this[table.deliveryId],
                channel = this[table.channel],
                templateCode = this[table.templateCode],
                aggregateId = this[table.aggregateId],
                status = this[table.status],
                attemptCount = this[table.attemptCount],
                nextAttemptAt = this[table.nextAttemptAt],
                claimOwner = this[table.claimOwner],
                claimUntil = this[table.claimUntil],
                failureCode = this[table.failureCode]
            ),
            createdBy = this[table.createdBy],
            createdAt = this[table.createdAt],
            updatedBy = this[table.updatedBy],
            updatedAt = this[table.updatedAt]
        )

    /**
     * enqueue은 예약 제어면 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param request 예약 제어면 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun enqueue(
        request: NotificationRequest,
        now: Instant,
    ): NotificationDeliveryRecord {
        val inserted = table
            .insertIgnore {
                it[deliveryId] = request.deliveryId
                it[channel] = request.channel
                it[templateCode] = request.templateCode
                it[aggregateId] = request.aggregateId
                it[status] = NotificationDeliveryStatus.PENDING
                it[nextAttemptAt] = now
            }.insertedCount == 1

        log.debug { "notification_delivery_persisted inserted=$inserted" }

        return findByDeliveryId(request.deliveryId) ?: error("notification delivery disappeared after enqueue")
    }

    /**
     * findByDeliveryId은 예약 제어면의 commit된 source-of-truth를 읽어 호출자에게 반환하며, 알림이나 캐시만으로 현재 상태를 추정하지
     * 않습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param deliveryId 처리할 예약 제어면 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    fun findByDeliveryId(deliveryId: String): NotificationDeliveryRecord? =
        table
            .selectAll()
            .where { table.deliveryId eq deliveryId }
            .singleOrNull()
            ?.toEntity()
}

/** schema bootstrap과 PostgreSQL fixture에서 사용하는 application-owned table reference입니다. */
/** schema bootstrap과 PostgreSQL fixture에서 사용하는 application-owned table reference입니다. */
internal val reservationNotificationDeliveryTable: NotificationDeliveryTable =
    NotificationDeliveryTable
