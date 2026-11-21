package io.bluetape4k.workshop.operations.jobconsole.idempotency

import io.bluetape4k.javatimes.hours
import io.bluetape4k.javatimes.millis
import io.bluetape4k.javatimes.seconds
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.Duration
import java.util.*

/**
 * Immutable, bounded policy shared by every idempotency coordinator instance.
 *
 * The fingerprint intentionally contains no secrets. It is used for readiness
 * parity checks so that instances cannot silently run different admission or
 * replay limits.
 * @property ownerLease 재시도·임대·보존·처리 제한을 결정하는 시간 길이입니다. 장애 시 무한 대기나 중복 실행을 막는 정책 경계입니다.
 * @property prepareDeadline 운영 작업 콘솔의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock
 * 정책을 유지합니다.
 * @property waiterTimeout 재시도·임대·보존·처리 제한을 결정하는 시간 길이입니다. 장애 시 무한 대기나 중복 실행을 막는 정책 경계입니다.
 * @property maxWaitersPerKey 재시도·임대·보존·처리 제한을 결정하는 시간 길이입니다. 장애 시 무한 대기나 중복 실행을 막는 정책 경계입니다.
 * @property maxWaitersPerInstance 재시도·임대·보존·처리 제한을 결정하는 시간 길이입니다. 장애 시 무한 대기나 중복 실행을 막는 정책 경계입니다.
 * @property datasourcePoolSize 동시 처리량·할당량·집계 수량 또는 처리 진척도입니다. 음수·한도 초과를 검증하고 경쟁 요청에서도 원자적으로 갱신해야
 * 합니다.
 * @property idempotencyDbConcurrency 청구·정산에 사용되는 금액 또는 통화 값입니다. 반올림·통화 일치 규칙을 거친 뒤 저장하며 문자열 포맷만으로
 * 계산하지 않습니다.
 * @property ownerPrepareConcurrency 청구·정산에 사용되는 금액 또는 통화 값입니다. 반올림·통화 일치 규칙을 거친 뒤 저장하며 문자열 포맷만으로
 * 계산하지 않습니다.
 * @property connectionAcquireTimeout 재시도·임대·보존·처리 제한을 결정하는 시간 길이입니다. 장애 시 무한 대기나 중복 실행을 막는 정책
 * 경계입니다.
 * @property statementTimeout 현재 운영 작업 콘솔 처리의 분류 또는 수명주기 값입니다. 허용된 전이·재시도·오류 응답을 선택하는 기준이며 임의의 문자열로
 * 해석하지 않습니다.
 * @property pollInitialInterval 재시도·임대·보존·처리 제한을 결정하는 시간 길이입니다. 장애 시 무한 대기나 중복 실행을 막는 정책 경계입니다.
 * @property pollMaxInterval 재시도·임대·보존·처리 제한을 결정하는 시간 길이입니다. 장애 시 무한 대기나 중복 실행을 막는 정책 경계입니다.
 * @property janitorBatchSize 동시 처리량·할당량·집계 수량 또는 처리 진척도입니다. 음수·한도 초과를 검증하고 경쟁 요청에서도 원자적으로 갱신해야 합니다.
 * @property retention 재시도·임대·보존·처리 제한을 결정하는 시간 길이입니다. 장애 시 무한 대기나 중복 실행을 막는 정책 경계입니다.
 * @property maxKeyBytes 동시 처리량·할당량·집계 수량 또는 처리 진척도입니다. 음수·한도 초과를 검증하고 경쟁 요청에서도 원자적으로 갱신해야 합니다.
 * @property maxBodyBytes 동시 처리량·할당량·집계 수량 또는 처리 진척도입니다. 음수·한도 초과를 검증하고 경쟁 요청에서도 원자적으로 갱신해야 합니다.
 * @property maxReplayBytes 동시 처리량·할당량·집계 수량 또는 처리 진척도입니다. 음수·한도 초과를 검증하고 경쟁 요청에서도 원자적으로 갱신해야 합니다.
 * @property maxHeaderNames 운영 작업 콘솔 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property maxHeaderValues 운영 작업 콘솔 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property maxHeaderValueBytes 동시 처리량·할당량·집계 수량 또는 처리 진척도입니다. 음수·한도 초과를 검증하고 경쟁 요청에서도 원자적으로 갱신해야
 * 합니다.
 * @property maxAggregateHeaderBytes 동시 처리량·할당량·집계 수량 또는 처리 진척도입니다. 음수·한도 초과를 검증하고 경쟁 요청에서도 원자적으로
 * 갱신해야 합니다.
 */
internal data class JobSubmissionIdempotencyPolicy(
    /**
     * `ownerLease`은 작업 운영 콘솔에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
     *
     * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
     */
    val ownerLease: Duration = 30.seconds(),
    /**
     * `prepareDeadline`은 작업 운영 콘솔에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val prepareDeadline: Duration = 10.seconds(),
    /**
     * `waiterTimeout`은 작업 운영 콘솔에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val waiterTimeout: Duration = 2.seconds(),
    /**
     * `maxWaitersPerKey`은 작업 운영 콘솔에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val maxWaitersPerKey: Int = 2,
    /**
     * `maxWaitersPerInstance`은 작업 운영 콘솔에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val maxWaitersPerInstance: Int = 32,
    /**
     * `datasourcePoolSize`은 작업 운영 콘솔에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val datasourcePoolSize: Int = 8,
    /**
     * `idempotencyDbConcurrency`은 작업 운영 콘솔에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val idempotencyDbConcurrency: Int = 4,
    /**
     * `ownerPrepareConcurrency`은 작업 운영 콘솔에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
     *
     * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
     */
    val ownerPrepareConcurrency: Int = 8,
    /**
     * `connectionAcquireTimeout`은 작업 운영 콘솔에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val connectionAcquireTimeout: Duration = 250.millis(),
    /**
     * `statementTimeout`은 작업 운영 콘솔에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    val statementTimeout: Duration = 500.millis(),
    /**
     * `pollInitialInterval`은 작업 운영 콘솔에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val pollInitialInterval: Duration = 25.millis(),
    /**
     * `pollMaxInterval`은 작업 운영 콘솔에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val pollMaxInterval: Duration = 100.millis(),
    /**
     * `janitorBatchSize`은 작업 운영 콘솔에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val janitorBatchSize: Int = 100,
    /**
     * `retention`은 작업 운영 콘솔에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val retention: Duration = 1.hours(),
    /**
     * `maxKeyBytes`은 작업 운영 콘솔에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val maxKeyBytes: Int = 255,
    /**
     * `maxBodyBytes`은 작업 운영 콘솔에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val maxBodyBytes: Int = 64 * 1024,
    /**
     * `maxReplayBytes`은 작업 운영 콘솔에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val maxReplayBytes: Int = 64 * 1024,
    /**
     * `maxHeaderNames`은 작업 운영 콘솔에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
     *
     * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
     */
    val maxHeaderNames: Int = 8,
    /**
     * `maxHeaderValues`은 작업 운영 콘솔에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
     *
     * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
     */
    val maxHeaderValues: Int = 4,
    /**
     * `maxHeaderValueBytes`은 작업 운영 콘솔에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val maxHeaderValueBytes: Int = 4 * 1024,
    /**
     * `maxAggregateHeaderBytes`은 작업 운영 콘솔에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val maxAggregateHeaderBytes: Int = 16 * 1024,
) {

    init {
        require(ownerLease.hasPositiveDuration()) { "ownerLease must be positive" }
        require(prepareDeadline.hasPositiveDuration()) { "prepareDeadline must be positive" }
        require(waiterTimeout.hasPositiveDuration()) { "waiterTimeout must be positive" }
        require(ownerLease > prepareDeadline) { "ownerLease must exceed prepareDeadline" }
        require(maxWaitersPerKey > 0) { "maxWaitersPerKey must be positive" }
        require(maxWaitersPerInstance > 0) { "maxWaitersPerInstance must be positive" }
        require(maxWaitersPerKey <= maxWaitersPerInstance) {
            "maxWaitersPerKey must not exceed maxWaitersPerInstance"
        }
        require(datasourcePoolSize > 0) { "datasourcePoolSize must be positive" }
        require(idempotencyDbConcurrency in 1..datasourcePoolSize) {
            "idempotencyDbConcurrency must be within datasourcePoolSize"
        }
        require(ownerPrepareConcurrency > 0) { "ownerPrepareConcurrency must be positive" }
        require(connectionAcquireTimeout.hasPositiveDuration()) { "connectionAcquireTimeout must be positive" }
        require(statementTimeout.hasPositiveDuration()) { "statementTimeout must be positive" }
        require(statementTimeout.toMillis() > 0L) { "statementTimeout must be at least one millisecond" }
        require(pollInitialInterval.hasPositiveDuration()) { "pollInitialInterval must be positive" }
        require(pollMaxInterval.hasPositiveDuration()) { "pollMaxInterval must be positive" }
        require(pollInitialInterval <= pollMaxInterval) {
            "pollInitialInterval must not exceed pollMaxInterval"
        }
        require(janitorBatchSize > 0) { "janitorBatchSize must be positive" }
        require(retention.hasPositiveDuration()) { "retention must be positive" }
        require(maxKeyBytes in 1..MAX_KEY_BYTES) { "maxKeyBytes must be between 1 and $MAX_KEY_BYTES" }
        require(maxBodyBytes in 1..MAX_BODY_BYTES) { "maxBodyBytes must be between 1 and $MAX_BODY_BYTES" }
        require(maxReplayBytes in 1..maxBodyBytes) {
            "maxReplayBytes must be within maxBodyBytes"
        }
        require(maxHeaderNames in 1..MAX_HEADER_NAMES) {
            "maxHeaderNames must be between 1 and $MAX_HEADER_NAMES"
        }
        require(maxHeaderValues in 1..MAX_HEADER_VALUES) {
            "maxHeaderValues must be between 1 and $MAX_HEADER_VALUES"
        }
        require(maxHeaderValueBytes in 1..MAX_HEADER_VALUE_BYTES) {
            "maxHeaderValueBytes must be between 1 and $MAX_HEADER_VALUE_BYTES"
        }
        require(maxAggregateHeaderBytes in 1..MAX_AGGREGATE_HEADER_BYTES) {
            "maxAggregateHeaderBytes must be between 1 and $MAX_AGGREGATE_HEADER_BYTES"
        }
    }

    /** Stable readiness value; never include raw keys, scopes, or owner tokens. */
    /** Stable readiness value; never include raw keys, scopes, or owner tokens. */
    val fingerprint: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
        digest(
            domain = "job-console-idempotency-policy-v1",
            components = listOf(
                ownerLease,
                prepareDeadline,
                waiterTimeout,
                maxWaitersPerKey,
                maxWaitersPerInstance,
                datasourcePoolSize,
                idempotencyDbConcurrency,
                ownerPrepareConcurrency,
                connectionAcquireTimeout,
                statementTimeout,
                pollInitialInterval,
                pollMaxInterval,
                janitorBatchSize,
                retention,
                maxKeyBytes,
                maxBodyBytes,
                maxReplayBytes,
                maxHeaderNames,
                maxHeaderValues,
                maxHeaderValueBytes,
                maxAggregateHeaderBytes,
            ).map(Any::toString),
        )
    }

    /**
     * hasPositiveDuration은 운영 작업 콘솔의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    private fun Duration.hasPositiveDuration(): Boolean = !isZero && !isNegative

    private companion object {
        /**
         * `MAX_KEY_BYTES`은 작업 운영 콘솔에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
         *
         * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
         */
        const val MAX_KEY_BYTES = 255
        /**
         * `MAX_BODY_BYTES`은 작업 운영 콘솔에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
         *
         * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
         */
        const val MAX_BODY_BYTES = 64 * 1024
        /**
         * `MAX_HEADER_NAMES`은 작업 운영 콘솔에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
         *
         * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
         */
        const val MAX_HEADER_NAMES = 64
        /**
         * `MAX_HEADER_VALUES`은 작업 운영 콘솔에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
         *
         * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
         */
        const val MAX_HEADER_VALUES = 64
        /**
         * `MAX_HEADER_VALUE_BYTES`은 작업 운영 콘솔에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
         *
         * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
         */
        const val MAX_HEADER_VALUE_BYTES = 16 * 1024
        /**
         * `MAX_AGGREGATE_HEADER_BYTES`은 작업 운영 콘솔에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
         *
         * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
         */
        const val MAX_AGGREGATE_HEADER_BYTES = 64 * 1024

        /**
         * digest은 운영 작업 콘솔 요청의 인증·권한·무결성을 확인하고, 실패 시 민감한 내부 상태를 노출하지 않은 채 업무 처리를 차단합니다.
         *
         * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         *
         * @param domain 업무 처리에 필요한 domain 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
         * @param components 업무 처리에 필요한 components 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
         */
        fun digest(domain: String, components: List<String>): String {
            val messageDigest = MessageDigest.getInstance("SHA-256")
            update(messageDigest, domain.toByteArray(UTF_8))
            components.forEach { update(messageDigest, it.toByteArray(UTF_8)) }
            return messageDigest.digest().joinToString(separator = "") { byte ->
                "%02x".format(Locale.ROOT, byte.toInt() and 0xff)
            }
        }

        /**
         * update은 운영 작업 콘솔의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
         *
         * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         *
         * @param messageDigest 운영 작업 콘솔 상태 변경을 나타내는 이벤트 또는 저장 레코드입니다. 순서·revision·중복 여부를 보존해야 합니다.
         * @param bytes 업무 처리에 필요한 bytes 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
         */
        fun update(messageDigest: MessageDigest, bytes: ByteArray) {
            messageDigest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
            messageDigest.update(bytes)
        }
    }
}
