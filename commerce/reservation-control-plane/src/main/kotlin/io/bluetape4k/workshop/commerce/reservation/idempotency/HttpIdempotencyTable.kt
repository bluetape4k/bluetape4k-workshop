package io.bluetape4k.workshop.commerce.reservation.idempotency

import io.bluetape4k.exposed.core.auditable.AuditableLongIdTable
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/** tenant, operation, opaque key digest별로 replay 가능한 command result 하나를 저장합니다. */
/** tenant, operation, opaque key digest별로 replay 가능한 command result 하나를 저장합니다. */
internal object HttpIdempotencyTable: AuditableLongIdTable("reservation_http_idempotency") {
    /**
     * 좌석 예약·대기열 요청이 속한 테넌트 식별자입니다. 저장·이벤트·멱등성 범위를 이 값으로 분리합니다.
     */
    val tenantId = varchar("tenant_id", 80)
    /**
     * 좌석 예약·대기열 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    val operation = varchar("operation", 80)
    /**
     * 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
     */
    val keyDigest = char("key_digest", 64)
    /**
     * 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
     */
    val requestFingerprint = char("request_fingerprint", 64)
    /**
     * 좌석 예약·대기열의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
     */
    val status = enumerationByName<IdempotencyStatus>("status", 24)
    /**
     * 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
     */
    val ownerToken = javaUUID("owner_token")
    /**
     * 좌석 예약·대기열의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    val leaseUntil = timestamp("lease_until")
    /**
     * 좌석 예약·대기열의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
     */
    val responseStatus = integer("response_status").nullable()
    /**
     * 좌석 예약·대기열 경계의 전달 데이터입니다. 스키마·크기·무결성을 검증한 뒤 저장하거나 발행합니다.
     */
    val responseBody = text("response_body").nullable()
    /**
     * 좌석 예약·대기열의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    val expiresAt = timestamp("expires_at")

    init {
        uniqueIndex(tenantId, operation, keyDigest)
        index(false, expiresAt)
    }
}

/** bootstrap과 focused PostgreSQL test에서 사용하는 application-owned schema reference입니다. */
/** bootstrap과 focused PostgreSQL test에서 사용하는 application-owned schema reference입니다. */
internal val reservationHttpIdempotencyTable = HttpIdempotencyTable
