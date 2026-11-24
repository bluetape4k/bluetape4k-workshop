package io.bluetape4k.workshop.commerce.metering.eventsourcing.idempotency

import io.bluetape4k.io.digest.Digesters
import io.bluetape4k.support.requireNotBlank
import java.util.*
/**
 * CommandDigest은 사용량 계량·청구에서 사용하는 업무 객체입니다.
 * @property value 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */


@JvmInline
value class CommandDigest(val value: String) {
    init {
        require(value.length == SHA256_HEX_LENGTH) { "command_digest_invalid" }
    }

    private companion object {
        /**
         * 사용량 계량·청구 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        const val SHA256_HEX_LENGTH = 64
    }
}

/**
 * CommandFingerprint은 사용량 과금 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
object CommandFingerprint {
    /**
     * key은 사용량 계량·청구의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param rawKey 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     */
    fun key(rawKey: String): CommandDigest {
        require(rawKey.isNotBlank() && rawKey.length <= MAX_KEY_LENGTH) { "idempotency_key_invalid" }
        return sha256("billing-command-key-v1\n$rawKey")
    }

    /**
     * request은 사용량 계량·청구의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param operation 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param fields 업무 처리에 필요한 fields 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun request(operation: String, fields: Map<String, String>): CommandDigest {
        operation.requireNotBlank { "operation_invalid" }
        val canonical = buildString {
            append("billing-command-request-v1\n").append(operation.trim()).append('\n')
            fields.toSortedMap().forEach { (name, value) -> append(name).append('=').append(value).append('\n') }
        }
        return sha256(canonical)
    }

    /**
     * sha256은 사용량 계량·청구의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param value 업무 처리에 필요한 value 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun sha256(value: String): CommandDigest = CommandDigest(
        Digesters.SHA256.digestHexUtf8(value),
    )
    /**
     * 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
     */

    private const val MAX_KEY_LENGTH = 256
}
