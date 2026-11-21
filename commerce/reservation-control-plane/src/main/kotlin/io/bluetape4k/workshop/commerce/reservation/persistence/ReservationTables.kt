package io.bluetape4k.workshop.commerce.reservation.persistence

import io.bluetape4k.exposed.core.auditable.AuditableLongIdTable
import io.bluetape4k.workshop.commerce.reservation.domain.HoldState
import io.bluetape4k.workshop.commerce.reservation.domain.OfferState
import io.bluetape4k.workshop.commerce.reservation.domain.ResourceState
import io.bluetape4k.workshop.commerce.reservation.domain.WaitlistState
import io.bluetape4k.workshop.commerce.reservation.idempotency.reservationHttpIdempotencyTable
import io.bluetape4k.workshop.commerce.reservation.notification.reservationNotificationDeliveryTable
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestamp

/** capacity authority입니다. `revision`과 `occupiedCount`는 같은 SQL CAS로 update됩니다. */
/** capacity authority입니다. `revision`과 `occupiedCount`는 같은 SQL CAS로 update됩니다. */
internal object CapacityResourceTable: AuditableLongIdTable("reservation_capacity_resources") {
    /**
     * 좌석 예약·대기열의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
     */
    val code = varchar("code", 80).uniqueIndex()
    /**
     * 좌석 예약·대기열의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
     */
    val state = enumerationByName<ResourceState>("state", 16).default(ResourceState.OPEN)
    /**
     * `capacity`은 예약 제어면에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val capacity = integer("capacity")
    /**
     * `occupiedCount`은 예약 제어면에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val occupiedCount = integer("occupied_count").default(0)
    /**
     * 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값이 다르면 오래된 명령을 거부합니다.
     */
    val revision = long("revision").default(0)
    /**
     * 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값이 다르면 오래된 명령을 거부합니다.
     */
    val policyVersion = long("policy_version")
    /**
     * 좌석 예약·대기열 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    val timezone = varchar("timezone", 64).default("UTC")
}

/** bounded sweeper discovery를 위해 expiry로 index된 durable hold state입니다. */
/** bounded sweeper discovery를 위해 expiry로 index된 durable hold state입니다. */
internal object ReservationHoldTable: AuditableLongIdTable("reservation_holds") {
    /**
     * `resourceId`은 예약 제어면에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val resourceId = reference("resource_id", CapacityResourceTable, onDelete = ReferenceOption.RESTRICT).index()
    /**
     * 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
     */
    val ownerDigest = char("owner_digest", 64)
    /**
     * 좌석 예약·대기열의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
     */
    val state = enumerationByName<HoldState>("state", 32)
    /**
     * 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값이 다르면 오래된 명령을 거부합니다.
     */
    val revision = long("revision").default(0)
    /**
     * 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값이 다르면 오래된 명령을 거부합니다.
     */
    val policyVersion = long("policy_version")
    /**
     * 좌석 예약·대기열의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    val expiresAt = timestamp("expires_at")

    init {
        index(false, state, expiresAt, id)
    }
}

/** resource, state, sequence, stable id로 정렬되는 FIFO entry입니다. */
/** resource, state, sequence, stable id로 정렬되는 FIFO entry입니다. */
internal object WaitlistEntryTable: AuditableLongIdTable("reservation_waitlist_entries") {
    /**
     * `resourceId`은 예약 제어면에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val resourceId = reference("resource_id", CapacityResourceTable, onDelete = ReferenceOption.RESTRICT)
    /**
     * 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
     */
    val ownerDigest = char("owner_digest", 64)
    /**
     * 좌석 예약·대기열의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
     */
    val state = enumerationByName<WaitlistState>("state", 24)
    /**
     * 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값이 다르면 오래된 명령을 거부합니다.
     */
    val sequence = long("sequence")
    /**
     * 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값이 다르면 오래된 명령을 거부합니다.
     */
    val revision = long("revision").default(0)

    init {
        index(false, resourceId, state, sequence, id)
    }
}

/** waitlist entry당 하나의 expiring offer입니다. unique entry reference가 double promotion을 막습니다. */
/** waitlist entry당 하나의 expiring offer입니다. unique entry reference가 double promotion을 막습니다. */
internal object ReservationOfferTable: AuditableLongIdTable("reservation_offers") {
    /**
     * `resourceId`은 예약 제어면에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val resourceId = reference("resource_id", CapacityResourceTable, onDelete = ReferenceOption.RESTRICT).index()
    /**
     * `entryId`은 예약 제어면에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val entryId = reference("entry_id", WaitlistEntryTable, onDelete = ReferenceOption.RESTRICT).uniqueIndex()
    /**
     * 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
     */
    val ownerDigest = char("owner_digest", 64)
    /**
     * 좌석 예약·대기열의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
     */
    val state = enumerationByName<OfferState>("state", 24)
    /**
     * 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값이 다르면 오래된 명령을 거부합니다.
     */
    val revision = long("revision").default(0)
    /**
     * 좌석 예약·대기열의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    val expiresAt = timestamp("expires_at")

    init {
        index(false, state, expiresAt, id)
    }
}

/** aggregate identity와 revision으로 keying되는 append-only transition evidence입니다. */
/** aggregate identity와 revision으로 keying되는 append-only transition evidence입니다. */
internal object ReservationAuditTable: Table("reservation_transition_audits") {
    /**
     * 좌석 예약·대기열의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
     */
    val aggregateType = varchar("aggregate_type", 32)
    /**
     * `aggregateId`은 예약 제어면에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val aggregateId = long("aggregate_id")
    /**
     * 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값이 다르면 오래된 명령을 거부합니다.
     */
    val revision = long("revision")
    /**
     * 좌석 예약·대기열의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
     */
    val outcome = varchar("outcome", 32)
    /**
     * 좌석 예약·대기열의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
     */
    val reason = varchar("reason", 80).nullable()
    /**
     * 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
     */

    override val primaryKey = PrimaryKey(aggregateType, aggregateId, revision)
}

/** bootstrap과 isolated PostgreSQL fixture에서 사용하는 완전한 application-owned schema입니다. */
/** bootstrap과 isolated PostgreSQL fixture에서 사용하는 완전한 application-owned schema입니다. */
internal val reservationTables: Array<Table> = arrayOf(
    CapacityResourceTable,
    ReservationHoldTable,
    WaitlistEntryTable,
    ReservationOfferTable,
    ReservationAuditTable,
    reservationHttpIdempotencyTable,
    reservationNotificationDeliveryTable
)
