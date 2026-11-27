package io.bluetape4k.workshop.commerce.metering.idempotency

import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireInRange
import io.bluetape4k.workshop.shared.fingerprint.FingerprintEncoder
import java.math.BigDecimal
import java.time.Instant

/**
 * `IDEMPOTENCY_KEY_MAX_LENGTH`은 사용량 과금·정산에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
 *
 * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
 */
private const val IDEMPOTENCY_KEY_MAX_LENGTH = 256

@JvmInline
/**
 * Sha256Digest은 사용량 과금 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
value class Sha256Digest private constructor(val value: String) {
    companion object: KLogging() {
        /**
         * invoke은 사용량 계량·청구의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
         *
         * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         *
         * @param canonicalValue 업무 처리에 필요한 canonicalValue 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
         */
        operator fun invoke(canonicalValue: String): Sha256Digest =
            Sha256Digest(
                FingerprintEncoder.sha256Hex(canonicalValue),
            )

        /**
         * of은 사용량 계량·청구의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
         *
         * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         *
         * @param canonicalValue 업무 처리에 필요한 canonicalValue 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
         */
        fun of(canonicalValue: String): Sha256Digest = invoke(canonicalValue)
    }
}

/**
 * CommandFingerprint은 사용량 과금 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
object CommandFingerprint: KLogging() {

    /**
     * key은 사용량 계량·청구의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param rawKey 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     */
    fun key(rawKey: String): Sha256Digest {
        rawKey.length.requireInRange(1, IDEMPOTENCY_KEY_MAX_LENGTH, "rawKey.length")
        return Sha256Digest.of("metering-idempotency-key-v1\u0000$rawKey")
    }

    /**
     * request은 사용량 계량·청구의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param operation 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param fields 업무 처리에 필요한 fields 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun request(operation: String, fields: Map<String, String>): Sha256Digest {
        val canonical = buildString {
            append("metering-command-v1\n")
            append(operation.trim()).append('\n')
            fields.toSortedMap().forEach { (name, value) ->
                append(name).append('=').append(value).append('\n')
            }
        }
        return Sha256Digest(canonical)
    }

    /**
     * decimal은 사용량 계량·청구의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param value 업무 처리에 필요한 value 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun decimal(value: BigDecimal): String =
        value.stripTrailingZeros()
            .let { normalized ->
                if (normalized.compareTo(BigDecimal.ZERO) == 0) "0" else normalized.toPlainString()
            }

    /**
     * instant은 사용량 계량·청구의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param value 업무 처리에 필요한 value 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun instant(value: Instant): String = value.toString()
}
