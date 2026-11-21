package io.bluetape4k.workshop.commerce.reservation.application

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import io.bluetape4k.support.checkBeTrue
import io.bluetape4k.support.requireInRange
import io.bluetape4k.workshop.commerce.reservation.idempotency.AcquireResult
import io.bluetape4k.workshop.commerce.reservation.idempotency.HttpIdempotencyRepository
import io.bluetape4k.workshop.commerce.reservation.idempotency.IdempotencyFingerprint
import io.bluetape4k.workshop.commerce.reservation.idempotency.IdempotencyScope
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.io.Serializable
import java.time.Clock

/**
 * IdempotentCommandResult은 예약 제어면 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal data class IdempotentCommandResult<T>(
    /**
     * `status`은 예약 제어면에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    val status: Int,
    /**
     * `value`은 예약 제어면에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val value: T,
    /**
     * `replayed`은 예약 제어면에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val replayed: Boolean,
): Serializable {
    companion object {
        /**
         * `serialVersionUID`은 예약 제어면에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID = 1L
    }
}

/**
 * command mutation과 replay 가능한 HTTP body를 단일 PostgreSQL transaction에 저장합니다.
 * @property repository 좌석 예약·대기열 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property credentials 좌석 예약·대기열 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property objectMapper 좌석 예약·대기열 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property clock 좌석 예약·대기열 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property tenantId 좌석 예약·대기열 요청이 속한 테넌트 식별자입니다. 저장·이벤트·멱등성 범위를 이 값으로 분리합니다.
 */
@Service
internal class IdempotentReservationCommandService(
    /**
     * `repository`은 예약 제어면에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    private val repository: HttpIdempotencyRepository,
    /**
     * `credentials`은 예약 제어면에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val credentials: ReservationCredentialService,
    /**
     * `objectMapper`은 예약 제어면에서 저장 객체·외부 리소스·전송 경계를 가리키는 참조 값입니다.
     *
     * allowlist·sanitize·소유권 검사를 거친 뒤 API·저장소·이벤트에서 동일한 key 규칙을 사용합니다.
     */
    private val objectMapper: ObjectMapper,
    /**
     * `clock`은 예약 제어면에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val clock: Clock,
    @Value("\${reservation.tenant-id}") private val tenantId: String,
) {
    companion object: KLogging()
    
    /**
     * execute은 예약 제어면의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param operation 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param rawKey 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     * @param rawOwner 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     * @param canonicalPayload 예약 제어면 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     * @param successStatus 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param bodyType 예약 제어면 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     * @param action 업무 처리에 필요한 action 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    @Transactional
    fun <T: Any> execute(
        operation: String,
        rawKey: String,
        rawOwner: String,
        canonicalPayload: String,
        successStatus: Int,
        bodyType: Class<T>,
        action: () -> T,
    ): IdempotentCommandResult<T> {
        rawKey.length.requireInRange(16, 200, "rawKey.length")

        val ownerDigest = credentials.ownerDigest(rawOwner)
        val scope = IdempotencyScope(
            tenantId = tenantId,
            operation = operation,
            keyDigest = credentials.idempotencyDigest(tenantId, operation, rawKey)
        )
        val fingerprint = IdempotencyFingerprint.request(operation, "$canonicalPayload\nowner=$ownerDigest")
        val ownerToken = Uuid.V7.nextId()

        val acquired = repository.acquire(scope, fingerprint, ownerToken, clock.instant())
        return when (acquired) {
            is AcquireResult.New,
            is AcquireResult.Takeover,
                                        -> {
                val record =
                    when (acquired) {
                        is AcquireResult.New      -> acquired.record
                        is AcquireResult.Takeover -> acquired.record
                    }
                val response = action()
                val body = objectMapper.writeValueAsString(response)
                repository.finalize(record.id, ownerToken, successStatus, body, failed = false)
                    .checkBeTrue { "idempotency owner lost before command finalize" }
                log.info { "reservation_idempotency_completed operation=$operation status=$successStatus" }
                IdempotentCommandResult(successStatus, response, replayed = false)
            }
            is AcquireResult.Replay     -> {
                log.info { "reservation_idempotency_replayed operation=$operation status=${acquired.status}" }
                IdempotentCommandResult(
                    status = acquired.status,
                    value = objectMapper.readValue(acquired.body, bodyType),
                    replayed = true
                )
            }
            AcquireResult.FingerprintConflict -> {
                log.warn { "reservation_idempotency_rejected operation=$operation reason=FINGERPRINT_CONFLICT" }
                throw ReservationCommandException("IDEMPOTENCY_FINGERPRINT_CONFLICT", null, false)
            }
            is AcquireResult.InProgress -> {
                val retryAfter = acquired.retryAfter.seconds.coerceAtLeast(1)
                log.warn { "reservation_idempotency_rejected operation=$operation reason=COMMAND_IN_PROGRESS" }
                throw ReservationCommandException("COMMAND_IN_PROGRESS", null, true, retryAfter)
            }
        }
    }
}
